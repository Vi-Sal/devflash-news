CREATE TABLE sources (
 id bigserial PRIMARY KEY, name text NOT NULL, website_url text NOT NULL, feed_url text NOT NULL UNIQUE,
 source_type text NOT NULL, enabled boolean NOT NULL DEFAULT false, last_fetched_at timestamptz, last_success_at timestamptz,
 last_error text, etag text, last_modified text, next_fetch_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE articles (
 id bigserial PRIMARY KEY, source_id bigint NOT NULL REFERENCES sources, title text NOT NULL, original_title text NOT NULL,
 slug text NOT NULL UNIQUE, original_url text NOT NULL, canonical_url text NOT NULL UNIQUE, author text, source_description text,
 summary text, image_url text, published_at timestamptz NOT NULL, ingested_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(), reading_time_minutes integer NOT NULL DEFAULT 1 CHECK(reading_time_minutes>0),
 processing_status text NOT NULL DEFAULT 'PENDING', publication_status text NOT NULL DEFAULT 'DRAFT' CHECK(publication_status IN ('DRAFT','PUBLISHED')),
 featured boolean NOT NULL DEFAULT false,
 search_vector tsvector GENERATED ALWAYS AS (setweight(to_tsvector('english',coalesce(title,'')),'A') || setweight(to_tsvector('english',coalesce(summary,'')),'B')) STORED
);
CREATE INDEX articles_page_idx ON articles(published_at DESC,id DESC) WHERE publication_status='PUBLISHED';
CREATE INDEX articles_source_idx ON articles(source_id,publication_status);
CREATE INDEX articles_search_idx ON articles USING gin(search_vector);
CREATE TABLE categories (id bigserial PRIMARY KEY,name text NOT NULL,slug text UNIQUE NOT NULL,description text NOT NULL DEFAULT '',icon text NOT NULL DEFAULT 'layers',accent_color text NOT NULL DEFAULT '#2583ff',enabled boolean NOT NULL DEFAULT true,created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE topics (id bigserial PRIMARY KEY,name text NOT NULL,slug text UNIQUE NOT NULL,description text NOT NULL DEFAULT '',created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE article_categories (article_id bigint REFERENCES articles ON DELETE CASCADE,category_id bigint REFERENCES categories ON DELETE CASCADE,confidence numeric NOT NULL DEFAULT 1, PRIMARY KEY(article_id,category_id));
CREATE INDEX category_articles_idx ON article_categories(category_id,article_id);
CREATE TABLE article_topics (article_id bigint REFERENCES articles ON DELETE CASCADE,topic_id bigint REFERENCES topics ON DELETE CASCADE,PRIMARY KEY(article_id,topic_id));
CREATE INDEX topic_articles_idx ON article_topics(topic_id,article_id);
CREATE TABLE article_enrichments (id bigserial PRIMARY KEY,article_id bigint UNIQUE NOT NULL REFERENCES articles ON DELETE CASCADE,what_happened text,why_it_matters text,key_takeaway text,developer_impact text,provider_name text,model_name text,generation_status text,generated_at timestamptz,attempts integer NOT NULL DEFAULT 0);
CREATE TABLE security_details (article_id bigint PRIMARY KEY REFERENCES articles ON DELETE CASCADE,affected_company text,affected_product text,incident_type text,severity text,cve_id text,cvss_score numeric CHECK(cvss_score BETWEEN 0 AND 10),affected_versions text,exploitation_status text,patch_status text,mitigation text,verified_at timestamptz);
CREATE TABLE ingestion_runs (id bigserial PRIMARY KEY,source_id bigint REFERENCES sources,started_at timestamptz NOT NULL DEFAULT now(),finished_at timestamptz,status text NOT NULL,fetched_count integer DEFAULT 0,inserted_count integer DEFAULT 0,duplicate_count integer DEFAULT 0,error_count integer DEFAULT 0,error_message text);
CREATE INDEX runs_time_idx ON ingestion_runs(started_at DESC);
CREATE TABLE ingestion_locks (lock_name text PRIMARY KEY,owner_id text NOT NULL,expires_at timestamptz NOT NULL);
CREATE TABLE admin_users (id bigserial PRIMARY KEY,username text NOT NULL UNIQUE,password_hash text NOT NULL,enabled boolean NOT NULL DEFAULT true,created_at timestamptz NOT NULL DEFAULT now(),last_login_at timestamptz);
CREATE TABLE admin_audit_logs (id bigserial PRIMARY KEY,admin_user_id bigint REFERENCES admin_users,action text NOT NULL,entity_type text NOT NULL,entity_id text,created_at timestamptz DEFAULT now(),safe_metadata jsonb NOT NULL DEFAULT '{}');
CREATE TABLE site_settings (setting_key text PRIMARY KEY,setting_value text NOT NULL,updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE article_metrics (article_id bigint PRIMARY KEY REFERENCES articles ON DELETE CASCADE,view_count bigint NOT NULL DEFAULT 0,share_count bigint NOT NULL DEFAULT 0,updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE ai_usage (usage_date date PRIMARY KEY,calls integer NOT NULL DEFAULT 0);
