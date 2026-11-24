UPDATE sources
SET enabled = true, updated_at = now()
WHERE source_type = 'CISA_KEV'
  AND feed_url = 'https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json'
  AND enabled = false;
