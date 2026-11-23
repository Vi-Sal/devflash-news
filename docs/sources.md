# News sources and import behavior

The initial catalogue enables two project-owned feeds. Their official endpoints returned HTTP 200 and parseable feeds during implementation on November 23, 2026.

| Publisher | Feed | Reuse basis |
| --- | --- | --- |
| The Go Blog | [Atom feed](https://go.dev/blog/feed.atom) | Go licenses website content under CC BY 4.0 except where noted. Code has separate licensing. [Website copyright](https://go.dev/copyright) · [Site terms](https://go.dev/tos) |
| Kubernetes Blog | [RSS feed](https://kubernetes.io/feed.xml) | The Kubernetes website repository includes the blog and publishes its content under CC BY 4.0. [Blog](https://kubernetes.io/blog/) · [Repository license](https://github.com/kubernetes/website/blob/main/LICENSE) |

Preview text comes from the feed description or summary. DevFlash removes markup, truncates long descriptions and retains the publisher, item URL, supplied byline and publication timestamp. The article page links to [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) for these sources. A provider-generated explanation is an adaptation of the preview. Check individual exceptions before syndicating third-party material. Embedded article HTML and remote page bodies are never used for previews.

## Add a publisher

1. Verify the publisher controls the feed. Review its terms or obtain permission for the intended excerpts, attribution, image reuse and optional AI processing. Keep the evidence with the publisher record in your operational documentation.
2. Add the exact DNS hostname to the comma-separated server variable `FEED_ALLOWED_HOSTS`. Include existing hosts and any authorized redirect destination. Restart the backend.
3. Create the source in Admin → Sources and choose RSS, Atom, or JSON Feed v1/v1.1. Use its HTTPS URL. Test it, enable it, then run ingestion.
4. Review original links, dates, categories, attribution and any required license notices. Disable a source when its permissions or feed behavior change.

## Ingestion and retry behavior

Each run acquires a renewable PostgreSQL lease. Runs after a crashed worker can reclaim its expired lease and mark interrupted history entries failed. Each article and its classifications share one transaction. A unique canonical URL excludes repeat entries, including links with common campaign tags.

The collector validates the HTTPS destination and redirect hops, enforces a public-IP policy in the connection's DNS resolver, caps feeds at 3 MiB and restricts XML nesting. It drops entries with missing publication dates, future dates or invalid links. Parser formats include RSS, Atom and JSON Feed v1/v1.1. Only same-host HTTPS publisher image URLs are retained. The feed endpoint and the original story can have different hosts.

Read time refers to the displayed short brief. Source classification combines the headline, publisher-provided category terms, source identity and excerpt. Items without a relevant classification and items labeled as sponsored, rumors or giveaways enter the moderation queue as drafts. A single publisher-supplied CVE can be recorded. Further incident claims, scores and mitigations require additional evidence.

The per-source batch limit counts **new articles**. A large backlog can take multiple runs. HTTP validators advance after a complete pass. Timeouts and server errors receive at most two retries with backoff. HTTP 429 and rate-limited 503 responses defer collection according to Retry-After. The Admin source test reads a feed without inserting articles. It can test disabled sources.

## Optional enrichment

Set the server variables `AI_ENABLED=true`, `AI_API_KEY` and `AI_MODEL` to enable the OpenAI adapter. Choose a Chat Completions model that accepts JSON object output. In Admin → Settings enable AI and set a positive daily request budget. Each collection run can process up to four eligible articles. The service sends the publisher, original headline and cleaned preview to the provider. It requires at least 100 characters of preview text, a supporting passage and a valid structured response. Unsupported explanations remain empty.

The service reserves budget before calling the provider and counts requests, including unsuccessful requests. Days follow Asia/Phnom_Penh. Each article permits two reserved requests across retries and administrator regeneration. Provider timeouts do not prevent reading the stored publisher preview. Read operations never trigger model calls. [Provider API](https://developers.openai.com/api/reference/resources/chat/)

## Optional official security catalogue

Select `CISA_KEV` and use the exact [official JSON download](https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json) to import CISA’s dated catalogue additions from the last 30 days. This fixed endpoint is built into the allowlist. On November 23, 2026 it returned HTTP 200 and 1,790 records. CISA distributes the dataset under CC0. [Official catalogue](https://www.cisa.gov/known-exploited-vulnerabilities-catalog) · [CISA data license](https://github.com/cisagov/kev-data/blob/develop/LICENSE)

An article links to its CVE in CISA’s catalogue. Its publication day is the UTC date the item was added to the catalogue. The publisher supplies the vulnerability name, short description, vendor, product, identifier and recommended action. Exploitation status reflects KEV inclusion. The catalogue does not supply CVSS, affected versions, or patch availability. DevFlash leaves those fields empty.
