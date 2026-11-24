INSERT INTO sources(name,website_url,feed_url,source_type,enabled)
VALUES ('Federal Reserve Board','https://www.federalreserve.gov/paymentsystems.htm',
        'https://www.federalreserve.gov/feeds/press_other.xml','RSS',true)
ON CONFLICT(feed_url) DO NOTHING;
