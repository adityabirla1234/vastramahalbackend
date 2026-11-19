-- Run once in the Supabase SQL Editor, after the application has created its tables.
--
-- Supabase publishes every table in the "public" schema through its auto-generated REST API (the Data API), reachable
-- with the project's anon key. This backend never uses that API: it connects directly as the "postgres" role, which owns
-- the tables and is therefore not affected by anything below.
--
-- 1. Take the Data API's roles (anon, authenticated) off every table, now and for tables Hibernate creates later.
-- 2. Turn on Row Level Security everywhere as a second lock (no policies = the API roles see nothing).
-- Simplest extra step: Supabase dashboard > Project Settings > Data API > turn the Data API off entirely.

DO $$
DECLARE
    t record;
    api_role text;
BEGIN
    FOREACH api_role IN ARRAY ARRAY['anon', 'authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = api_role) THEN
            EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', api_role);
            EXECUTE format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON TABLES FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON SEQUENCES FROM %I', api_role);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM %I', api_role);
        END IF;
    END LOOP;

    FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' LOOP
        EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t.tablename);
    END LOOP;
END $$;

-- Check: every table should show rowsecurity = true.
SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;
