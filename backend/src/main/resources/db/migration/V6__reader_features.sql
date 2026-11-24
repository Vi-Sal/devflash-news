CREATE TABLE daily_digests (
    digest_date date PRIMARY KEY,
    generated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE daily_digest_articles (
    digest_date date NOT NULL REFERENCES daily_digests(digest_date) ON DELETE CASCADE,
    article_id bigint NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    position integer NOT NULL CHECK (position BETWEEN 1 AND 10),
    PRIMARY KEY (digest_date, article_id),
    UNIQUE (digest_date, position)
);

CREATE INDEX daily_digest_articles_article_idx ON daily_digest_articles(article_id);
CREATE INDEX IF NOT EXISTS security_details_cve_idx ON security_details(upper(cve_id)) WHERE cve_id IS NOT NULL;

ALTER TABLE daily_digests ENABLE ROW LEVEL SECURITY;
ALTER TABLE daily_digest_articles ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON daily_digests, daily_digest_articles FROM PUBLIC;
DO $$
DECLARE role_name text;
BEGIN
    FOR role_name IN SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated')
    LOOP
        EXECUTE format('REVOKE ALL ON daily_digests, daily_digest_articles FROM %I', role_name);
    END LOOP;
END $$;

ALTER TABLE security_details ADD COLUMN verification_status text CHECK (
    verification_status IN ('Confirmed', 'Reported but unverified', 'Investigation ongoing',
                            'Vulnerability disclosed', 'Active exploitation confirmed')
);
ALTER TABLE ai_usage ADD COLUMN input_tokens bigint NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    ADD COLUMN output_tokens bigint NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    ADD COLUMN estimated_cost_usd numeric(16,8) CHECK (estimated_cost_usd >= 0);
