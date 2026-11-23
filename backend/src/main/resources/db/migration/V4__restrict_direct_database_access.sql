-- The Spring JDBC role owns these tables. Reader traffic reaches them through the API.
-- Supabase installations can grant Data API roles privileges on new public tables.
DO $$
DECLARE
    relation text;
    api_role text;
    serial_sequence text;
BEGIN
    FOREACH relation IN ARRAY ARRAY[
        'articles','sources','categories','topics','article_categories','article_topics',
        'article_enrichments','security_details','article_metrics','ingestion_runs',
        'ingestion_locks','ai_usage','admin_users','admin_audit_logs','site_settings'
    ] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', relation);
        EXECUTE format('REVOKE ALL ON TABLE %I FROM PUBLIC', relation);
        SELECT pg_get_serial_sequence(relation, a.attname) INTO serial_sequence
        FROM pg_attribute a WHERE a.attrelid=to_regclass(relation) AND a.attname='id';
        FOR api_role IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon','authenticated') LOOP
            EXECUTE format('REVOKE ALL ON TABLE %I FROM %I', relation, api_role);
            IF serial_sequence IS NOT NULL THEN
                EXECUTE format('REVOKE ALL ON SEQUENCE %s FROM %I', serial_sequence, api_role);
            END IF;
        END LOOP;
    END LOOP;
END $$;
