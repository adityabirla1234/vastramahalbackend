package com.rentalshop.backend.migration;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-time copy of the old Aiven MySQL data into this PostgreSQL (Supabase) database, on startup. Does nothing unless
 * MYSQL_IMPORT_ENABLED=true (app.mysql-import.enabled). See postgreSQL_migration/README.md.
 *
 * Runs before every other startup runner (CancelledBookingPurgeRunner, LegacySoftDeleteCleanupRunner) and while it runs
 * MysqlImportGateFilter answers every request with 503, so nothing reads or writes a half-filled database. In order:
 * Hibernate creates the schema (forced here, since spring.main.lazy-initialization would otherwise delay it); the copy is skipped if mysql_import_status says it already happened; it refuses to
 * write into tables that already have rows (unless MYSQL_IMPORT_REPLACE_EXISTING=true, which empties them first);
 * then every table that exists in both databases is copied, parents before children, from one consistent MySQL
 * snapshot, in ONE PostgreSQL transaction. Identity sequences are moved past the copied ids and row counts are compared
 * before commit. Any failure rolls everything back and stops the application, so it never runs on a partial copy.
 *
 * Logs table names and counts only, never row values.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MysqlImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MysqlImportRunner.class);
    private static final String STATUS_TABLE = "mysql_import_status";
    private static final int BATCH = 500;

    private record Column(String name, String type, boolean nullable, boolean hasDefault) {}

    private record TablePlan(String table, List<Column> columns) {}

    private final boolean enabled;
    private final String mysqlUrl;
    private final String mysqlUser;
    private final String mysqlPassword;
    private final boolean replaceExisting;
    private final DataSource dataSource;
    private final ObjectProvider<EntityManagerFactory> entityManagerFactory;
    private volatile boolean pending;

    public MysqlImportRunner(@Value("${app.mysql-import.enabled:false}") boolean enabled,
                             @Value("${app.mysql-import.url:}") String mysqlUrl,
                             @Value("${app.mysql-import.username:}") String mysqlUser,
                             @Value("${app.mysql-import.password:}") String mysqlPassword,
                             @Value("${app.mysql-import.replace-existing:false}") boolean replaceExisting,
                             DataSource dataSource, ObjectProvider<EntityManagerFactory> entityManagerFactory) {
        this.enabled = enabled;
        this.mysqlUrl = mysqlUrl;
        this.mysqlUser = mysqlUser;
        this.mysqlPassword = mysqlPassword;
        this.replaceExisting = replaceExisting;
        this.dataSource = dataSource;
        this.entityManagerFactory = entityManagerFactory;
        this.pending = enabled;
    }

    /** True from startup until the import has finished or been skipped. */
    public boolean isPending() {
        return pending;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!enabled) return;
        try {
            // Hibernate (ddl-auto=update) creates the tables when the EntityManagerFactory starts; copy only after that.
            entityManagerFactory.getObject();
            try (Connection pg = dataSource.getConnection()) {
                if (alreadyImported(pg)) return;
                try (Connection my = openMysql()) {
                    importAll(my, pg);
                }
            }
        } finally {
            pending = false;
        }
    }

    private boolean alreadyImported(Connection pg) throws SQLException {
        try (Statement s = pg.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS " + STATUS_TABLE + " (id SMALLINT PRIMARY KEY, "
                    + "imported_at TIMESTAMPTZ NOT NULL, tables_copied INTEGER NOT NULL, rows_copied BIGINT NOT NULL)");
            try (ResultSet rs = s.executeQuery("SELECT imported_at FROM " + STATUS_TABLE + " WHERE id = 1")) {
                if (rs.next()) {
                    log.info("MYSQL_IMPORT_SKIPPED already imported at {}. Remove MYSQL_IMPORT_ENABLED and the other "
                            + "MYSQL_IMPORT_* settings.", rs.getObject(1));
                    return true;
                }
            }
        }
        return false;
    }

    private Connection openMysql() throws Exception {
        if (!mysqlUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("MYSQL_IMPORT_URL must look like jdbc:mysql://<host>:<port>/<database>?sslMode=REQUIRED");
        }
        Properties p = new Properties();
        p.setProperty("user", mysqlUser);
        p.setProperty("password", mysqlPassword);
        // Hibernate wrote every timestamp in UTC (hibernate.jdbc.time_zone=UTC); read them back unchanged.
        p.setProperty("connectionTimeZone", "UTC");
        p.setProperty("forceConnectionTimeZoneToSession", "true");
        p.setProperty("zeroDateTimeBehavior", "CONVERT_TO_NULL");
        p.setProperty("connectTimeout", "30000");
        Driver driver = (Driver) Class.forName("com.mysql.cj.jdbc.Driver").getDeclaredConstructor().newInstance();
        Connection my = driver.connect(mysqlUrl, p);
        if (my == null) throw new IllegalStateException("MYSQL_IMPORT_URL was not accepted by the MySQL driver");
        try (Statement s = my.createStatement()) {
            // One consistent snapshot of every table, even if the old deployment still writes during the copy.
            s.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            s.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
        }
        return my;
    }

    private void importAll(Connection my, Connection pg) throws SQLException {
        long started = System.currentTimeMillis();
        Set<String> source = mysqlTables(my);
        Map<String, List<Column>> target = pgColumns(pg);
        target.remove(STATUS_TABLE);

        Set<String> common = new TreeSet<>(target.keySet());
        common.retainAll(source);
        if (common.isEmpty()) {
            throw new IllegalStateException("MySQL and PostgreSQL have no table in common. Check MYSQL_IMPORT_URL points at the app's database.");
        }
        for (String t : new TreeSet<>(source)) {
            if (!common.contains(t)) log.warn("MYSQL_IMPORT_TABLE_SKIPPED table={} exists only in MySQL, its rows are not copied", t);
        }

        List<TablePlan> plan = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String table : fkOrder(pg, common)) {
            Set<String> myCols = new HashSet<>(mysqlColumns(my, table));
            List<Column> cols = new ArrayList<>();
            Set<String> pgNames = new HashSet<>();
            for (Column c : target.get(table)) {
                pgNames.add(c.name());
                if (myCols.contains(c.name())) cols.add(c);
                else if (!c.nullable() && !c.hasDefault()) problems.add(table + "." + c.name() + " is NOT NULL here but missing in MySQL");
            }
            for (String c : new TreeSet<>(myCols)) {
                if (!pgNames.contains(c)) log.warn("MYSQL_IMPORT_COLUMN_SKIPPED column={}.{} exists only in MySQL, its values are not copied", table, c);
            }
            plan.add(new TablePlan(table, cols));
        }
        if (!problems.isEmpty()) throw new IllegalStateException("Cannot import: " + String.join("; ", problems));

        List<String> nonEmpty = new ArrayList<>();
        for (TablePlan tp : plan) if (pgCount(pg, tp.table()) > 0) nonEmpty.add(tp.table());
        if (!nonEmpty.isEmpty() && !replaceExisting) {
            throw new IllegalStateException("These PostgreSQL tables already have rows: " + nonEmpty
                    + ". Set MYSQL_IMPORT_REPLACE_EXISTING=true to empty them and import again.");
        }

        Map<String, Long> copied = new LinkedHashMap<>();
        pg.setAutoCommit(false);
        try {
            if (!nonEmpty.isEmpty()) {
                List<String> names = plan.stream().map(tp -> pgName(tp.table())).toList();
                try (Statement s = pg.createStatement()) {
                    s.execute("TRUNCATE " + String.join(", ", names) + " RESTART IDENTITY CASCADE");
                }
                log.info("MYSQL_IMPORT_TRUNCATED tables={}", nonEmpty);
            }
            for (TablePlan tp : plan) {
                long rows = copyTable(my, pg, tp);
                copied.put(tp.table(), rows);
                log.info("MYSQL_IMPORT_TABLE table={} rows={}", tp.table(), rows);
            }
            for (TablePlan tp : plan) resetSequences(pg, tp);
            for (TablePlan tp : plan) {
                long inMysql = mysqlCount(my, tp.table());
                long inPg = pgCount(pg, tp.table());
                if (inMysql != inPg) {
                    throw new IllegalStateException("Row count differs for " + tp.table() + ": MySQL " + inMysql + ", PostgreSQL " + inPg);
                }
            }
            long total = copied.values().stream().mapToLong(Long::longValue).sum();
            try (PreparedStatement ps = pg.prepareStatement("INSERT INTO " + STATUS_TABLE
                    + " (id, imported_at, tables_copied, rows_copied) VALUES (1, now(), ?, ?)")) {
                ps.setInt(1, plan.size());
                ps.setLong(2, total);
                ps.executeUpdate();
            }
            pg.commit();
            log.info("MYSQL_IMPORT_DONE tables={} rows={} ms={}. Remove MYSQL_IMPORT_ENABLED and the other MYSQL_IMPORT_* settings.",
                    plan.size(), total, System.currentTimeMillis() - started);
        } catch (SQLException | RuntimeException e) {
            pg.rollback();
            log.error("MYSQL_IMPORT_FAILED nothing was written to PostgreSQL. type={}", e.getClass().getName());
            throw e;
        } finally {
            pg.setAutoCommit(true);
        }
        warnAboutLateWrites(my, copied);
    }

    /** Rows written to MySQL after the snapshot (an old deployment still running) were not copied. */
    private void warnAboutLateWrites(Connection my, Map<String, Long> copied) throws SQLException {
        try (Statement s = my.createStatement()) {
            s.execute("COMMIT");
        }
        for (Map.Entry<String, Long> e : copied.entrySet()) {
            long now = mysqlCount(my, e.getKey());
            if (now != e.getValue()) {
                log.warn("MYSQL_IMPORT_LATE_WRITES table={} copied={} mysqlNow={}. Something wrote to MySQL during the import; "
                        + "see postgreSQL_migration/README.md.", e.getKey(), e.getValue(), now);
            }
        }
    }

    private long copyTable(Connection my, Connection pg, TablePlan tp) throws SQLException {
        List<Column> cols = tp.columns();
        if (cols.isEmpty()) return 0;
        StringBuilder select = new StringBuilder("SELECT ");
        StringBuilder insert = new StringBuilder("INSERT INTO ").append(pgName(tp.table())).append(" (");
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            String sep = i == 0 ? "" : ", ";
            select.append(sep).append(mysqlName(cols.get(i).name()));
            insert.append(sep).append(pgName(cols.get(i).name()));
            values.append(sep).append('?');
        }
        select.append(" FROM ").append(mysqlName(tp.table()));
        insert.append(") VALUES (").append(values).append(')');

        long rows = 0;
        try (Statement s = my.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            s.setFetchSize(Integer.MIN_VALUE); // stream rows instead of loading the whole table into memory
            try (ResultSet rs = s.executeQuery(select.toString());
                 PreparedStatement ps = pg.prepareStatement(insert.toString())) {
                while (rs.next()) {
                    for (int i = 0; i < cols.size(); i++) bind(rs, ps, i + 1, cols.get(i).type());
                    ps.addBatch();
                    if (++rows % BATCH == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
        }
        return rows;
    }

    /** Reads column i from MySQL in the form the PostgreSQL column type needs. */
    private static void bind(ResultSet rs, PreparedStatement ps, int i, String pgType) throws SQLException {
        switch (pgType) {
            case "boolean" -> {
                boolean v = rs.getBoolean(i);
                ps.setObject(i, rs.wasNull() ? null : v, Types.BOOLEAN);
            }
            case "smallint", "integer" -> {
                int v = rs.getInt(i);
                ps.setObject(i, rs.wasNull() ? null : v, Types.INTEGER);
            }
            case "bigint" -> {
                long v = rs.getLong(i);
                ps.setObject(i, rs.wasNull() ? null : v, Types.BIGINT);
            }
            case "numeric" -> ps.setBigDecimal(i, rs.getBigDecimal(i));
            case "real", "double precision" -> {
                double v = rs.getDouble(i);
                ps.setObject(i, rs.wasNull() ? null : v, Types.DOUBLE);
            }
            case "date" -> ps.setObject(i, rs.getObject(i, LocalDate.class), Types.DATE);
            case "time without time zone" -> ps.setObject(i, rs.getObject(i, LocalTime.class), Types.TIME);
            case "timestamp without time zone" -> ps.setObject(i, rs.getObject(i, LocalDateTime.class), Types.TIMESTAMP);
            case "timestamp with time zone" -> {
                LocalDateTime v = rs.getObject(i, LocalDateTime.class);
                ps.setObject(i, v == null ? null : v.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
            }
            case "bytea" -> ps.setBytes(i, rs.getBytes(i));
            case "text", "character varying", "character" -> ps.setString(i, rs.getString(i));
            // json, jsonb, uuid, ...: sent as untyped text, PostgreSQL casts it to the column type.
            default -> ps.setObject(i, rs.getString(i), Types.OTHER);
        }
    }

    private static void resetSequences(Connection pg, TablePlan tp) throws SQLException {
        for (Column c : tp.columns()) {
            if (!Set.of("smallint", "integer", "bigint").contains(c.type())) continue;
            String seq;
            try (PreparedStatement ps = pg.prepareStatement("SELECT pg_get_serial_sequence(?, ?)")) {
                ps.setString(1, "public." + pgName(tp.table()));
                ps.setString(2, c.name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    seq = rs.getString(1);
                }
            }
            if (seq == null) continue;
            try (PreparedStatement ps = pg.prepareStatement("SELECT setval(?, COALESCE((SELECT MAX(" + pgName(c.name())
                    + ") FROM " + pgName(tp.table()) + "), 0) + 1, false)")) {
                ps.setString(1, seq);
                ps.executeQuery().close();
            }
        }
    }

    private static Set<String> mysqlTables(Connection my) throws SQLException {
        Set<String> out = new HashSet<>();
        try (Statement s = my.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static List<String> mysqlColumns(Connection my, String table) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = my.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static Map<String, List<Column>> pgColumns(Connection pg) throws SQLException {
        Map<String, List<Column>> out = new TreeMap<>();
        try (Statement s = pg.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT c.table_name, c.column_name, c.data_type, c.is_nullable, c.column_default, c.is_identity "
                        + "FROM information_schema.columns c JOIN information_schema.tables t "
                        + "ON t.table_schema = c.table_schema AND t.table_name = c.table_name "
                        + "WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE' "
                        + "ORDER BY c.table_name, c.ordinal_position")) {
            while (rs.next()) {
                out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Column(rs.getString(2), rs.getString(3),
                        "YES".equals(rs.getString(4)), rs.getString(5) != null || "YES".equals(rs.getString(6))));
            }
        }
        return out;
    }

    /** Parents before children, from the foreign keys PostgreSQL knows about. */
    private static List<String> fkOrder(Connection pg, Set<String> tables) throws SQLException {
        Map<String, Set<String>> parents = new TreeMap<>();
        for (String t : tables) parents.put(t, new HashSet<>());
        try (Statement s = pg.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT child.relname, parent.relname FROM pg_constraint c "
                        + "JOIN pg_class child ON child.oid = c.conrelid JOIN pg_class parent ON parent.oid = c.confrelid "
                        + "JOIN pg_namespace n ON n.oid = child.relnamespace WHERE c.contype = 'f' AND n.nspname = 'public'")) {
            while (rs.next()) {
                String child = rs.getString(1), parent = rs.getString(2);
                if (parents.containsKey(child) && parents.containsKey(parent) && !child.equals(parent)) parents.get(child).add(parent);
            }
        }
        List<String> ordered = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (ordered.size() < tables.size()) {
            List<String> ready = parents.entrySet().stream()
                    .filter(e -> !done.contains(e.getKey()) && done.containsAll(e.getValue())).map(Map.Entry::getKey).toList();
            if (ready.isEmpty()) throw new IllegalStateException("Foreign keys form a cycle between the remaining tables");
            ordered.addAll(ready);
            done.addAll(ready);
        }
        return ordered;
    }

    private static long mysqlCount(Connection my, String table) throws SQLException {
        try (Statement s = my.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + mysqlName(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long pgCount(Connection pg, String table) throws SQLException {
        try (Statement s = pg.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + pgName(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String mysqlName(String name) {
        return '`' + name.replace("`", "``") + '`';
    }

    private static String pgName(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
