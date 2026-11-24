# DevFlash

DevFlash is a technology news reader with a PostgreSQL-backed newsroom. Spring Boot serves the React application and the REST API from one origin. Readers can browse, search, bookmark, and share stories without an account.

## Start locally

Use Java 21, Maven 3.9+, Node 22.12+, and PostgreSQL 17 or 18. Run commands from the repository root unless a step specifies another directory.

**Reader preview without a database.** This explicit sample edition uses dated historical announcements with publisher links. Its bookmarks and reading history are separate from the live reader.

```sh
cd frontend
npm ci
npm run demo
```

Open the local address printed by Vite. Production builds exclude the sample data module.

**Full application.**

1. Create a PostgreSQL database and a login that owns its application schema. Use a dedicated database or schema. Flyway creates the DevFlash tables at startup.
2. Copy `.env.example` to `.env`. Set the JDBC URL, database login and password. For local PostgreSQL, a typical URL is `jdbc:postgresql://127.0.0.1:5432/devflash`. Supply a fresh bootstrap username and password and a separate scheduler token. Generate each secret separately with `openssl rand -hex 32`.
3. Export the variables in your shell. For a shell-compatible `.env`, use `set -a`, `. ./.env`, then `set +a`. Shell-quote values containing spaces or shell metacharacters.
4. Build the reader and run the application.

```sh
npm --prefix frontend ci
npm --prefix frontend run build
mvn -f backend/pom.xml clean package
java -jar backend/target/devflash-1.0.0.jar
```

Open [the local reader](http://localhost:8080) or [the newsroom](http://localhost:8080/admin). Sign in, open **Ingestion**, and start collection. An empty database shows an empty feed until articles arrive. The default limit is 30 new articles per source per run. A second import can collect the rest of a large feed.

For frontend development, keep Spring Boot on port 8080 and run `npm --prefix frontend run dev` in another terminal. Vite forwards `/api` requests to Spring. For backend development, `mvn -f backend/pom.xml spring-boot:run` starts the application directly.

To collect with the scheduler credential from a shell containing the configured variables, run

```sh
curl --fail-with-body -X POST \
  -H "Authorization: Bearer $INGESTION_TOKEN" \
  http://localhost:8080/api/v1/internal/ingestion/run
```

## Application architecture

| Component | Responsibility |
| --- | --- |
| React, TypeScript, Vite, Tailwind | Glacier Glass reader, responsive layout, theme selection, source attribution, news and admin routes |
| TanStack Query and React Router | Cursor-based infinite feeds, cancellation, retry, cached back-navigation, explicit refresh for incoming news |
| Spring Boot 4.2 and Java 21 | MVC routes, validated API requests, same-origin session authentication, crawler-readable article documents |
| Spring Data JPA and Spring JDBC | Article repository, parameterized PostgreSQL feed and search queries, transactional moderation and ingestion |
| PostgreSQL and Flyway | News, categories, topics, taxonomy overrides, enrichments, advisory metadata, counters, source health, ingestion leases, and audit records |
| Maven and Docker | Reader assets and backend compiled into one executable jar and one Render web service |

The default sources are the Kubernetes Blog, The Go Blog, and CISA’s official Known Exploited Vulnerabilities catalogue. Review the [source register, permission notes, collection rules, and AI configuration](docs/sources.md) before adding feeds.

### Reading and discovery

- Latest and category feeds order by publication time and article ID. For You adds a six-hour ranking boost for editorial features and a twelve-hour boost for matches to up to 12 followed topics. The selected lead uses the newest story marked featured within seven days, with the newest published story as a fallback. The feed excludes the lead on For You. Cursors include the active ranking value, publication time, and article ID. Page size defaults to 10 and is capped at 30.
- PostgreSQL full-text search covers titles, summaries, topics, and publisher names. The reader supports date ranges and category filters. The GIN article index covers the headline and summary vector.
- Article details link to original reporting. The collector retains the original title, byline, description, URL, and date separately from editorial overrides. A missing preview sends the reader to the publisher. Optional structured explanations appear only when available.
- Trending uses the configured recent-day window. Each topic receives one point per distinct publisher, plus individual story contributions. A contribution starts at one, adds a featured-story bonus and a logarithmically capped engagement term, then decays with age in days. The UI reports story counts. Canonical duplicates contribute once.
- Daily Tech Briefing saves selected article IDs and their order in PostgreSQL. It prioritizes editorial features, sourced security signals, recency, and diversity across categories and publishers, and suppresses near-identical headlines. It selects up to ten stories toward a 10–15 minute estimate when enough coverage exists. Startup fills in missing editions from the last 30 days. Successful ingestion refreshes today and yesterday using Asia/Phnom_Penh date boundaries. Older editions remain accessible at `/daily/{date}`.
- Followed topics, bookmarks, read status, reading activity, goals, recent searches, and appearance are browser-local. Learning progress starts when an article opens or is manually marked read. It sums the stored article reading estimates and uses the browser’s local calendar for streaks. Saved articles support text search, category filters, saved order, and read status. Opening a story or sharing it records a measured event. The server counts at most one event of each type per article per session, with a bounded per-session collection.
- Security Watch at `/security` filters source-backed alerts by severity, verification, affected company or product, CVE, incident type, patch status, and publication date. The API uses `/api/v1/security` with cursor pagination. CISA entries can identify confirmed exploitation. Unverified articles are clearly labeled and absent metadata stays blank.
- The topic directory and topic feeds are available at `/topics` and `/topics/{slug}`. The API accepts `/api/v1/articles?feed=for-you&following=java,spring-boot`. Briefing APIs are `/api/v1/daily`, `/api/v1/daily/{date}`, and `/api/v1/daily/archive`.
- Spring renders article titles, descriptions, canonical links, Open Graph tags, JSON-LD, and readable summary markup on direct article requests. The sitemap covers the newest 5,000 published articles and the main public pages.

### Newsroom

The newsroom supports article moderation, multi-category and topic assignment, duplicate suppression, topic merging, source testing, manual ingestion, retries, settings, and audit history. Disabling a source stops future imports. Editorial duplicates keep a suppression record so a subsequent feed import cannot restore the same canonical URL.

Administrators authenticate with Spring Security, a session cookie, bcrypt hashes, CSRF tokens, and a bounded login throttle. Bootstrapping creates the first account once, inside a PostgreSQL transaction lock. The username must be 3–80 supported characters. The password must contain at least 16 characters and fit bcrypt’s limit of 72 UTF-8 bytes. Remove both bootstrap variables after the first successful login. Subsequent restarts retain the account.

## Configuration

| Variable | Purpose |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Server-only PostgreSQL JDBC connection. Use SSL on Supabase. |
| `PORT` | HTTP listener, default 8080. Render supplies a port. |
| `SPRING_PROFILES_ACTIVE` | Use `local` for local HTTP, `prod` on Render for secure cookies and structured JSON logs. |
| `ADMIN_BOOTSTRAP_USERNAME`, `ADMIN_BOOTSTRAP_PASSWORD` | One-time first administrator provisioning. |
| `INGESTION_TOKEN` | Dedicated bearer secret for the external scheduler. |
| `FEED_ALLOWED_HOSTS` | Exact approved publisher hostnames separated by commas. Defaults to `go.dev,kubernetes.io`. The exact official CISA catalogue URL is separately recognized. |
| `YOUTUBE_CHANNEL_URL` | Optional official channel link. The sidebar displays the card when a channel URL is configured. Admin settings can override it. |
| `AI_ENABLED`, `AI_API_KEY`, `AI_MODEL` | Optional server-side provider configuration. Also enable AI in newsroom settings and assign a positive daily request budget. |
| `AI_INPUT_COST_PER_MILLION_USD`, `AI_OUTPUT_COST_PER_MILLION_USD` | Optional model price inputs for the newsroom’s estimated AI cost. Reported provider token counts are stored regardless of price configuration. |
| `APP_BASE_URL` | Canonical site origin. Defaults to Render’s external URL when present, otherwise localhost:8080. Set this for a custom domain. |
| `JAVA_TOOL_OPTIONS` | Optional JVM sizing. The container defaults to a 62% maximum heap, Serial GC, and exit on out-of-memory. |

The connection pool caps at three connections and allows zero idle connections. The production JVM needs memory outside its heap for threads, class metadata, buffers, and the HTTP server.

## Database migrations

| Migration | Change |
| --- | --- |
| V1 | Creates normalized newsroom tables, unique canonical URLs and slugs, composite feed indexes, full-text search vector and GIN index, relationships, authentication, audit, and AI usage tables. |
| V2 | Adds the five default categories and the two verified enabled feeds. |
| V3 | Registers the optional CISA catalogue, disabled by default. |
| V4 | Enables row-level security on newsroom tables and removes direct public and Supabase browser-role grants. The server connects as the schema/table owner. |
| V5 | Adds source-scoped entry identifiers and article image metadata. Clears feed validators once so existing entries can be enriched on their next import. |
| V6 | Creates persistent daily editions and their ordered article links, adds an advisory verification field and CVE index, and extends AI usage with token totals and optional USD estimates. |
| V7 | Enables the existing official CISA catalogue source. |

Flyway owns schema changes. Hibernate validates the mapping at startup.

Set `IMAGE_LICENSED_HOSTS` to comma-separated publisher and image hosts whose images you have permission to display with publisher attribution. Article-page requests also require the host in `FEED_ALLOWED_HOSTS`. A blank image allowlist uses category illustrations. Import checks Media RSS content, thumbnails, image enclosures, page social metadata and JSON Feed images. Stored image metadata serves reader requests.

## Deploy on Render

1. Push this repository to your Git provider. In Render, create a Blueprint from `render.yaml`, or create one Docker web service from the repository root. The multistage Dockerfile builds React and packages it into Spring Boot. Set the health path to `/api/v1/health`. [Render deployments](https://render.com/docs/deploys)
2. In the Supabase dashboard, open the project connection settings. Use the project’s Session pooler: set `DB_URL=jdbc:postgresql://aws-1-ap-northeast-1.pooler.supabase.com:5432/postgres?sslmode=require` and `DB_USERNAME=postgres.vjuukcwdrhgrbwwxbkhm`. Set `DB_PASSWORD` separately in Render secrets. For server identity verification, mount the official Supabase CA and use `sslmode=verify-full&sslrootcert=/path/to/ca.crt`. [Supabase connection methods](https://supabase.com/docs/guides/database/connecting-to-postgres)
3. Set the one-time bootstrap credentials and production profile. The Blueprint generates an ingestion token. Set the channel and AI configuration only when those services are ready. Deploy, sign in, and remove the bootstrap credentials.
4. Add GitHub Actions repository secrets `DEVFLASH_BASE_URL` and `INGESTION_TOKEN`. Use the HTTPS origin and the same scheduler token as Render. Run **Collect news** manually once. The workflow runs hourly at minute 17 from the default branch. Free instances can take time to wake and scheduled Actions can be delayed. [Render free instances](https://render.com/docs/free)
5. Check the ingestion history and published stories. The lease prevents overlapping collectors. A scheduled run can resume after an expired lease or retry a publisher after its backoff window.

### Post-deployment verification

- [ ] Health returns HTTP 200 with `data.status` equal to `UP`.
- [ ] Flyway has applied all seven migrations. The JDBC role owns the application tables.
- [ ] Logged-out access to admin APIs is denied. Administrator login succeeds over HTTPS, session cookies are secure, and logout revokes access.
- [ ] A manual import records per-source results. Repeating a completed import produces no duplicate articles.
- [ ] Article links, pagination, search, categories, saved items, daily briefing, and dark mode work on the deployed origin.
- [ ] A direct article URL contains its own title, canonical URL, and Open Graph metadata in the HTTP response. Verify `/robots.txt` and `/sitemap.xml`.
- [ ] The scheduled workflow completes, and logs contain request and ingestion information without secrets.
- [ ] A configured YouTube action targets the intended channel. If AI is enabled, verify one grounded explanation and its budget entry before raising the cap.

## Tests

```sh
npm --prefix frontend ci
npm --prefix frontend run build
(cd frontend && npx playwright install chromium && npm test)
mvn -f backend/pom.xml test
```

For the database integration suite, create a disposable database named `devflash_test` on loopback. Set `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` to that database, then run

```sh
export DEVFLASH_TEST_DB_URL="$DB_URL"
export DEVFLASH_TEST_DB_USERNAME="$DB_USERNAME"
export DEVFLASH_TEST_DB_PASSWORD="$DB_PASSWORD"
export DEVFLASH_ADMIN_TEST_DB=true
mvn -f backend/pom.xml clean verify
```

Set `DEVFLASH_TEST_LIVE_FEEDS=true` to include outbound publisher tests. Test fixtures and cleanup are confined to temporary schemas. For full-stack browser checks, start the packaged service against the isolated database and supply `DEVFLASH_TEST_BASE_URL`, `DEVFLASH_TEST_ADMIN_USERNAME`, and `DEVFLASH_TEST_ADMIN_PASSWORD` to `npm test`. Playwright uses its installed Chromium or a browser selected through `PLAYWRIGHT_CHROMIUM_EXECUTABLE`.

The verification workflow builds both applications, runs browser and database tests, builds the Docker image and scans it. See the [implementation checklist and verification record](docs/IMPLEMENTATION.md) for checks actually run in this workspace.
