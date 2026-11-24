# Delivery checklist and verification

| Order | Implementation phase | Result |
| --- | --- | --- |
| 1 | Foundation | React/Vite and Java 21 builds, environment configuration, four Flyway migrations, PostgreSQL integration, and Maven packaging. |
| 2 | Glacier Glass UI | Implemented the supplied homepage layout, recreated the supplied bolt-and-brackets mark, and generated original article artwork. Compared browser captures and adjusted hero spacing, category width, background contrast, and mobile image cropping. |
| 3 | Public backend | Implemented published article projections, category and topic queries, stable cursors, full-text search, rankings, briefing selection, daily counts, and share metadata. |
| 4 | Collection | Implemented RSS, Atom, JSON Feed, conditional requests, source limits, classification, duplicate detection, renewable locking, audit history, and the protected scheduler. |
| 5 | Reader integration | Connected the reader to the API, including infinite scrolling, article detail, search filters, local bookmarks, read status, theme preferences, and explicit refresh. |
| 6 | Administration | Implemented administrator bootstrap and sessions, CSRF, role checks, moderation, taxonomy editing and merging, source testing, ingestion monitoring, settings, and audit logs. |
| 7 | Enrichment | Implemented source excerpts, optional budgeted AI summaries, a defensive CISA advisory adapter, related articles, trending topics, and a configurable YouTube channel. |
| 8 | Release preparation | Packaged and ran the application, updated dependencies in response to advisory findings, tested the production profile, and supplied the Dockerfile, Render Blueprint, CI, scheduled import, and operator guide. |

## Verified locally on November 23, 2026

| Check | Observed result |
| --- | --- |
| Frontend production build | Strict TypeScript check and Vite build passed. The production distribution excludes the sample archive. |
| Backend build | Java 21 and Spring Boot 4.2.0 produced an executable jar with the reader and its assets. |
| Backend tests | 22 passed, 0 failed, 0 skipped, including opt-in live feed and database cases. |
| Browser tests | 13 passed in Chrome, including two checks against the packaged application and a real local PostgreSQL database. |
| PostgreSQL | Tested on isolated PostgreSQL 18.7. Fresh schema migrations and a repeat Flyway run passed. Temporary test schemas were removed after use. |
| Collection | The packaged collector added 40 articles on its first batch and 20 on its second. The next run added zero. The two default sources supplied 50 Kubernetes and 10 Go articles. The separate adapter test also contacted CISA. |
| API and crawler checks | Traversed 60 distinct published IDs through every cursor page. Checked public endpoints, original attribution, article-specific Open Graph and JSON-LD, canonical URLs, sitemap, robots file, 400 invalid-cursor response, 404 missing article, and 401 protected API. |
| Security | Full administrator sign-in/out, CSRF and role checks passed. Production profile smoke test verified the Secure, HttpOnly and SameSite=Strict cookie, CSP, and canonical origin from configuration. |
| Dependency inventory | npm audit reported zero vulnerabilities. OSV returned zero matching advisories for the 109 resolved Maven runtime packages after upgrading Spring Boot and Tomcat. This records the advisory databases at the time of the checks. |
| Display | Checked a 1536×1024 desktop viewport and a 320px mobile viewport. Browser tests cover search, filtering, bookmarks across reload, share-link fallback, theme persistence, pagination, retries, and new-story refresh. |

[Desktop capture](screenshots/desktop.jpg) · [Mobile capture](screenshots/mobile.jpg)

## External verification

- The workstation has no Docker engine. The GitHub verification workflow builds and scans the container in a Docker-capable runner.
- The Render service, Supabase credentials, and GitHub scheduler secrets have not been provisioned from this workspace. The application ran against local PostgreSQL.
- The AI adapter’s fallback, retry, per-article and daily limits were tested with an isolated provider fixture. A live model request requires provider credentials and an explicitly enabled budget.
- YouTube linking requires the owner’s channel URL. Historical examples appear only in the explicit sample edition.

## V3 reader upgrade

- Expanded the Glacier Glass reader with the new hero, database-selected featured card, briefing preview, responsive navigation, security highlights, and learning progress.
- Daily editions persist ordered story selections in PostgreSQL. Startup backfills recent missing dates. The existing authenticated collector refreshes today and yesterday. The reader loads stored editions and available source-grounded notes.
- Added Security Watch with date and metadata filters, the topic directory with browser-local following, and a For You ranking with bounded recency boosts. Saved articles now have search, category filters, saved order, and read status.
- V6 creates `daily_digests` and `daily_digest_articles`, adds `security_details.verification_status` and a CVE index, and adds token and optional estimated cost totals to `ai_usage`. V1–V5 are preserved.
- Local verification included a fresh migration and a V5-to-V6 upgrade on isolated PostgreSQL, 23 passing backend tests, 17 passing browser tests, a successful production frontend build, and a Linux AMD64 Docker build. The packaged application returned a ten-item cursor page, a featured story, security rows, and seven dated archive entries from the local database. Desktop and 320px mobile layouts were checked.
- Production Supabase was inspected read-only and reported V5 with 40 published articles at the start of work. V6 is pending production deployment. Deploy the current branch through the existing Render Docker service, verify the V6 Flyway entry and health endpoint, and run **Collect news** once from GitHub Actions if the scheduled collector has yet to run. The OpenAI path requires provider configuration. Cost estimates require model price inputs.
