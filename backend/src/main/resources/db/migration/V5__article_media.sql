ALTER TABLE articles ADD COLUMN source_item_id text,
  ADD COLUMN image_source text, ADD COLUMN image_alt text,
  ADD COLUMN image_attribution text, ADD COLUMN image_status text NOT NULL DEFAULT 'UNREVIEWED';
CREATE UNIQUE INDEX article_source_item_idx ON articles(source_id,source_item_id) WHERE source_item_id IS NOT NULL;
-- Revisit existing feed entries once to discover identifiers and media.
UPDATE sources SET etag=NULL,last_modified=NULL;
