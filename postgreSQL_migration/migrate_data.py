#!/usr/bin/env python3
"""
Copy every row from the old Aiven MySQL database into the new Supabase PostgreSQL database.

The PostgreSQL tables must already exist (created by the application's Hibernate ddl-auto=update on its
first start against Supabase, and for vastramahal optionally by schema.sql). This script only moves data:

  * copies every table that exists in both databases, only the columns that exist in both,
  * converts MySQL values to what PostgreSQL expects (bit(1)/tinyint -> boolean, TIME -> time, JSON -> jsonb, ...),
  * inserts parents before children (foreign-key order), all in ONE transaction: it either fully succeeds or
    leaves Supabase untouched,
  * moves every identity sequence past the highest copied id, so new rows do not collide,
  * compares row counts table by table at the end.

Usage (settings come from environment variables or a .env file next to this script, see migration.env.example):

  python migrate_data.py --dry-run     # connect, show the plan and row counts, change nothing
  python migrate_data.py               # copy; refuses if any target table already has rows
  python migrate_data.py --truncate    # empty the target tables first (e.g. rows the app created at first start)
"""
import argparse
import datetime as dt
import os
import ssl
import sys
from decimal import Decimal
from pathlib import Path

try:
    import psycopg
    import pymysql
    import pymysql.cursors
    from psycopg import sql
except ImportError:
    sys.exit("Missing libraries. Run: pip install -r requirements.txt")

BATCH = 1000
BOOL_TYPES = {"boolean"}
JSON_TYPES = {"json", "jsonb"}
TEXT_TYPES = {"text", "character varying", "character"}
TIME_TYPES = {"time without time zone", "time with time zone"}
TIMESTAMP_TYPES = {"timestamp without time zone", "timestamp with time zone", "date"}


def load_env_file():
    env_file = Path(__file__).with_name(".env")
    if not env_file.exists():
        return
    for line in env_file.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def need(name):
    value = os.environ.get(name)
    if not value:
        sys.exit(f"Setting {name} is missing (environment variable or postgreSQL_migration/.env).")
    return value


def connect_mysql():
    ctx = ssl.create_default_context()
    ca = os.environ.get("MYSQL_SSL_CA")
    if ca:
        ctx.load_verify_locations(ca)
    else:
        # Aiven signs its MySQL certificates with its own CA (downloadable from the service page). Without it
        # the connection is still encrypted, just not verified.
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        print("note: MYSQL_SSL_CA not set, TLS to MySQL is encrypted but the certificate is not verified")
    use_ssl = os.environ.get("MYSQL_SSL", "true").lower() != "false"
    conn = pymysql.connect(
        host=need("MYSQL_HOST"),
        port=int(os.environ.get("MYSQL_PORT", "3306")),
        user=need("MYSQL_USER"),
        password=os.environ.get("MYSQL_PASSWORD", ""),
        database=need("MYSQL_DATABASE"),
        ssl=ctx if use_ssl else None,
        charset="utf8mb4",
        connect_timeout=30,
        read_timeout=600,
    )
    with conn.cursor() as cur:
        # Hibernate wrote every timestamp in UTC (hibernate.jdbc.time_zone=UTC); read them back the same way.
        cur.execute("SET time_zone = '+00:00'")
        # One consistent snapshot of every table, even if something still writes during the copy.
        cur.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        cur.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
    return conn


def connect_pg():
    conn = psycopg.connect(need("PG_URL"), connect_timeout=30, autocommit=False)
    conn.execute("SET TIME ZONE 'UTC'")
    conn.commit()
    return conn


def mysql_tables(my):
    with my.cursor() as cur:
        cur.execute("SELECT table_name FROM information_schema.tables "
                    "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")
        return {r[0] for r in cur.fetchall()}


def mysql_columns(my, table):
    with my.cursor() as cur:
        cur.execute("SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema = DATABASE() AND table_name = %s ORDER BY ordinal_position", (table,))
        return [r[0] for r in cur.fetchall()]


def pg_tables(pg):
    rows = pg.execute("SELECT table_name FROM information_schema.tables "
                      "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'").fetchall()
    return {r[0] for r in rows}


def pg_columns(pg, table):
    rows = pg.execute("SELECT column_name, data_type, is_nullable, column_default, is_identity "
                      "FROM information_schema.columns WHERE table_schema = 'public' AND table_name = %s "
                      "ORDER BY ordinal_position", (table,)).fetchall()
    return [{"name": r[0], "type": r[1], "nullable": r[2] == "YES",
             "has_default": r[3] is not None or r[4] == "YES"} for r in rows]


def pg_fk_order(pg, tables):
    """Parents before children, from the foreign keys PostgreSQL knows about."""
    rows = pg.execute("""
        SELECT child.relname, parent.relname
        FROM pg_constraint c
        JOIN pg_class child ON child.oid = c.conrelid
        JOIN pg_class parent ON parent.oid = c.confrelid
        JOIN pg_namespace n ON n.oid = child.relnamespace
        WHERE c.contype = 'f' AND n.nspname = 'public'
    """).fetchall()
    parents = {t: set() for t in tables}
    for child, parent in rows:
        if child in parents and parent in parents and child != parent:
            parents[child].add(parent)
    ordered, done = [], set()
    while len(ordered) < len(tables):
        ready = sorted(t for t in tables if t not in done and parents[t] <= done)
        if not ready:
            raise SystemExit(f"Foreign keys form a cycle between: {sorted(set(tables) - done)}")
        ordered.extend(ready)
        done.update(ready)
    return ordered


def convert(value, pg_type):
    if value is None:
        return None
    if pg_type in BOOL_TYPES:
        if isinstance(value, (bytes, bytearray)):
            return any(value)
        if isinstance(value, str):
            return value.strip().lower() in ("1", "true", "t", "y", "yes")
        return bool(value)
    if pg_type in TIME_TYPES and isinstance(value, dt.timedelta):
        seconds = int(value.total_seconds())
        if not 0 <= seconds < 86400:
            raise ValueError(f"TIME value {value} is outside one day and cannot be stored as a PostgreSQL time")
        return (dt.datetime.min + value).time()
    if pg_type in TIMESTAMP_TYPES and isinstance(value, str) and value.startswith("0000-00-00"):
        return None  # MySQL "zero date"
    if isinstance(value, (bytes, bytearray)) and (pg_type in TEXT_TYPES or pg_type in JSON_TYPES):
        return value.decode("utf-8")
    if pg_type in ("integer", "bigint", "smallint") and isinstance(value, (bytes, bytearray)):
        return int.from_bytes(value, "big")
    if pg_type == "numeric" and isinstance(value, float):
        return Decimal(str(value))
    return value


def count(pg_or_my, table, is_pg):
    if is_pg:
        return pg_or_my.execute(sql.SQL("SELECT COUNT(*) FROM {}").format(sql.Identifier(table))).fetchone()[0]
    with pg_or_my.cursor() as cur:
        cur.execute(f"SELECT COUNT(*) FROM `{table}`")
        return cur.fetchone()[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dry-run", action="store_true", help="show the plan and counts, change nothing")
    parser.add_argument("--truncate", action="store_true", help="empty the target tables before copying")
    args = parser.parse_args()

    load_env_file()
    my = connect_mysql()
    pg = connect_pg()

    src, dst = mysql_tables(my), pg_tables(pg)
    common = src & dst
    if not common:
        sys.exit("No table exists in both databases. Start the application once against Supabase first, "
                 "so Hibernate creates the tables (see README.md).")
    for t in sorted(src - dst):
        print(f"skip   {t}: only in MySQL (no matching table in PostgreSQL, its rows are NOT copied)")
    for t in sorted(dst - src):
        print(f"note   {t}: only in PostgreSQL, left as it is")

    order = pg_fk_order(pg, common)
    plan, problems = [], []
    for table in order:
        my_cols = mysql_columns(my, table)
        pg_cols = {c["name"]: c for c in pg_columns(pg, table)}
        cols = [c for c in my_cols if c in pg_cols]
        for c in my_cols:
            if c not in pg_cols:
                print(f"warn   {table}.{c}: only in MySQL, this column's values are NOT copied")
        for name, c in pg_cols.items():
            if name not in my_cols and not c["nullable"] and not c["has_default"]:
                problems.append(f"{table}.{name} is NOT NULL in PostgreSQL but does not exist in MySQL")
        plan.append((table, cols, [pg_cols[c]["type"] for c in cols]))
    if problems:
        sys.exit("Cannot copy:\n  " + "\n  ".join(problems))

    print(f"\n{'table':32} {'mysql rows':>11} {'pg rows now':>12}")
    non_empty = []
    for table, _, _ in plan:
        m, p = count(my, table, False), count(pg, table, True)
        print(f"{table:32} {m:>11} {p:>12}")
        if p:
            non_empty.append(table)
    pg.rollback()

    if args.dry_run:
        print("\nDry run: nothing was changed.")
        return
    if non_empty and not args.truncate:
        sys.exit(f"\nThese PostgreSQL tables already have rows: {', '.join(non_empty)}.\n"
                 "Run again with --truncate to empty them first (they are emptied inside the same transaction).")

    try:
        if args.truncate:
            pg.execute(sql.SQL("TRUNCATE {} RESTART IDENTITY CASCADE").format(
                sql.SQL(", ").join(sql.Identifier(t) for t, _, _ in plan)))
        for table, cols, types in plan:
            placeholders = sql.SQL(", ").join(
                sql.SQL("%s::{}").format(sql.SQL(t)) if t in JSON_TYPES else sql.SQL("%s") for t in types)
            insert = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
                sql.Identifier(table), sql.SQL(", ").join(sql.Identifier(c) for c in cols), placeholders)
            select = "SELECT " + ", ".join(f"`{c}`" for c in cols) + f" FROM `{table}`"
            copied = 0
            with my.cursor(pymysql.cursors.SSCursor) as src_cur, pg.cursor() as dst_cur:
                src_cur.execute(select)
                while True:
                    rows = src_cur.fetchmany(BATCH)
                    if not rows:
                        break
                    dst_cur.executemany(insert, [tuple(convert(v, t) for v, t in zip(r, types)) for r in rows])
                    copied += len(rows)
            print(f"copied {table}: {copied} rows")

        for table, _, _ in plan:
            for c in pg_columns(pg, table):
                seq = pg.execute("SELECT pg_get_serial_sequence(%s, %s)", (f'public."{table}"', c["name"])).fetchone()[0]
                if seq:
                    pg.execute(sql.SQL("SELECT setval(%s, COALESCE((SELECT MAX({}) FROM {}), 0) + 1, false)").format(
                        sql.Identifier(c["name"]), sql.Identifier(table)), (seq,))
        pg.commit()
    except Exception:
        pg.rollback()
        print("\nFAILED. Nothing was written to PostgreSQL (the whole copy was rolled back).")
        raise

    print(f"\n{'table':32} {'mysql':>8} {'postgres':>9}  result")
    mismatches = 0
    for table, _, _ in plan:
        m, p = count(my, table, False), count(pg, table, True)
        ok = m == p
        mismatches += not ok
        print(f"{table:32} {m:>8} {p:>9}  {'OK' if ok else 'MISMATCH'}")
    pg.rollback()
    my.rollback()
    if mismatches:
        sys.exit(f"\n{mismatches} table(s) differ from the MySQL snapshot that was copied. "
                 "Check the app is stopped and re-run with --truncate.")
    print("\nDone. All row counts match.")


if __name__ == "__main__":
    main()
