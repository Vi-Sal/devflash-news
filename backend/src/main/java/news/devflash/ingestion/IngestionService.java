package news.devflash.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import news.devflash.articles.DailyBriefing;
import news.devflash.summarization.EnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IngestionService {
  private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
  private static final Pattern CVE = Pattern.compile("(?i)\\bCVE-\\d{4}-\\d{4,12}\\b");
  private static final String SOURCE_FIELDS =
      "select id,name,feed_url,source_type,etag,last_modified from sources ";
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final FeedClient client;
  private final FeedParser parser;
  private final EnrichmentService enrich;
  private final DailyBriefing briefing;

  public IngestionService(
      JdbcTemplate db,
      TransactionTemplate tx,
      FeedClient client,
      FeedParser parser,
      EnrichmentService enrich,
      DailyBriefing briefing) {
    this.db = db;
    this.tx = tx;
    this.client = client;
    this.parser = parser;
    this.enrich = enrich;
    this.briefing = briefing;
  }

  @org.springframework.beans.factory.annotation.Value("${IMAGE_LICENSED_HOSTS:}")
  private String licensedHosts = "";

  record Picture(String url, String source) {}

  boolean licensed(String url) {
    if (url == null) return false;
    String host = java.net.URI.create(url).getHost();
    return Arrays.stream(licensedHosts.split(","))
        .map(String::strip)
        .anyMatch(h -> h.equalsIgnoreCase(host));
  }

  private Picture picture(Source source, FeedParser.Story story) {
    if (!licensed(source.url)) return null;
    if (licensed(story.image()) && !"API".equals(story.imageSource()))
      return new Picture(story.image(), story.imageSource());
    if (licensed(story.canonical())
        && db.queryForObject(
            """
            select not exists(select 1 from articles where source_id=? and (canonical_url=? or source_item_id=?) and image_status in ('AVAILABLE','MISSING'))
            """,
            Boolean.class,
            source.id,
            story.canonical(),
            story.itemId())) {
      try {
        var page = client.fetchPage(story.canonical());
        var doc =
            org.jsoup.Jsoup.parse(
                new java.io.ByteArrayInputStream(page.body()), null, story.canonical());
        for (String key : List.of("og:image", "twitter:image")) {
          var el = doc.selectFirst("meta[property=" + key + "],meta[name=" + key + "]");
          if (el != null) {
            String url = FeedParser.imageUrl(story.canonical(), el.attr("content"));
            if (licensed(url)) return new Picture(url, key);
          }
        }
      } catch (Exception e) {
        log.warn(
            "event=image_lookup_failed source={} reason={}",
            source.id,
            e.getClass().getSimpleName());
      }
    }
    return licensed(story.image()) ? new Picture(story.image(), story.imageSource()) : null;
  }

  public record RunReport(int sources, int inserted, int duplicates, int errors) {}

  public record TestResult(int entries, int skipped) {}

  private record Source(
      long id, String name, String url, String type, String etag, String modified) {}

  private static class LostLease extends RuntimeException {}

  private static class Counts {
    int fetched, inserted, duplicates, errors;
  }

  public RunReport run(Long sourceId) {
    String owner = UUID.randomUUID().toString();
    int acquired =
        db.update(
            """
            insert into ingestion_locks(lock_name,owner_id,expires_at) values ('collect',?,now()+interval '3 minutes')
            on conflict(lock_name) do update set owner_id=excluded.owner_id,expires_at=excluded.expires_at
            where ingestion_locks.expires_at<=now()
            """,
            owner);
    if (acquired == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "An import is already running");
    try {
      db.update(
          """
          update ingestion_runs set status='FAILED',finished_at=now(),error_count=error_count+1,
          error_message='Previous worker stopped before completing' where status='RUNNING'
          """);
      String allReady = "where enabled and (next_fetch_at is null or next_fetch_at<=now())";
      List<Source> sources =
          sourceId == null
              ? sources(allReady + " order by last_fetched_at nulls first,id limit 100")
              : sources(allReady + " and id=?", sourceId);
      if (sourceId != null && sources.isEmpty())
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "Source is disabled, unavailable, or waiting for its next fetch");
      int limit =
          db
              .queryForList(
                  "select setting_value from site_settings where setting_key='ingestionBatchLimit'",
                  String.class)
              .stream()
              .findFirst()
              .map(
                  value -> {
                    try {
                      return Math.clamp(Integer.parseInt(value), 1, 100);
                    } catch (NumberFormatException e) {
                      return 30;
                    }
                  })
              .orElse(30);
      int inserted = 0, duplicates = 0, errors = 0;
      for (Source source : sources) {
        renew(owner);
        Counts c = collect(source, owner, limit);
        inserted += c.inserted;
        duplicates += c.duplicates;
        errors += c.errors;
      }
      renew(owner);
      briefing.refresh();
      enrich.retryPending(4);
      return new RunReport(sources.size(), inserted, duplicates, errors);
    } finally {
      db.update("delete from ingestion_locks where lock_name='collect' and owner_id=?", owner);
    }
  }

  private List<Source> sources(String condition, Object... args) {
    return db.query(
        SOURCE_FIELDS + condition,
        (r, i) ->
            new Source(
                r.getLong(1),
                r.getString(2),
                r.getString(3),
                r.getString(4),
                r.getString(5),
                r.getString(6)),
        args);
  }

  public TestResult test(long sourceId) {
    var rows = sources("where id=?", sourceId);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown source");
    Source s = rows.getFirst();
    try {
      checkAdapter(s);
      var response = client.fetch(s.url, null, null);
      var parsed = parser.parse(response.body(), s.type);
      return new TestResult(parsed.stories().size(), parsed.skipped());
    } catch (Exception failure) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, safeReason(failure));
    }
  }

  private static void checkAdapter(Source source) {
    if ("CISA_KEV".equals(source.type) && !FeedClient.KEV_URL.equals(source.url)) {
      throw new IllegalArgumentException("CISA entries require the official catalogue endpoint");
    }
  }

  public String retryArticle(long id) {
    return enrich.regenerate(id);
  }

  private Counts collect(Source s, String owner, int limit) {
    long runId =
        db.queryForObject(
            "insert into ingestion_runs(source_id,status) values (?,'RUNNING') returning id",
            Long.class,
            s.id);
    Counts counts = new Counts();
    try {
      checkAdapter(s);
      var response = client.fetch(s.url, s.etag, s.modified);
      boolean consumed = true;
      if (response.status() == 200) {
        var parsed = parser.parse(response.body(), s.type);
        counts.fetched = parsed.stories().size() + parsed.skipped();
        for (var story : parsed.stories()) {
          if (counts.inserted >= limit) {
            consumed = false;
            break;
          }
          try {
            Picture picture = picture(s, story);
            var id =
                tx.execute(
                    status -> {
                      renew(owner);
                      return insert(s, story, picture);
                    });
            if (id == null) counts.duplicates++;
            else counts.inserted++;
          } catch (LostLease lost) {
            throw lost;
          } catch (RuntimeException failure) {
            counts.errors++;
            log.warn(
                "event=feed_item_failed source={} run={} reason={}",
                s.id,
                runId,
                failure.getClass().getSimpleName());
          }
        }
      }
      boolean cacheable = consumed && counts.errors == 0;
      String etag = cacheable ? response.etag() : null;
      String modified = cacheable ? response.modified() : null;
      tx.executeWithoutResult(
          t -> {
            renew(owner);
            db.update(
                """
                update sources set last_fetched_at=now(),last_success_at=now(),next_fetch_at=null,
                last_error=?,etag=?,last_modified=?,updated_at=now() where id=? and feed_url=? and source_type=?
                """,
                counts.errors == 0 ? null : "Some items could not be stored",
                response.status() == 304 && etag == null ? s.etag : etag,
                response.status() == 304 && modified == null ? s.modified : modified,
                s.id,
                s.url,
                s.type);
            finish(runId, counts, counts.errors == 0 ? "SUCCESS" : "PARTIAL", null);
          });
    } catch (LostLease expired) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Import lease expired");
    } catch (Exception failure) {
      counts.errors++;
      String reason = safeReason(failure);
      Instant nextFetch = failure instanceof FeedClient.FetchFailure f ? f.retryAt : null;
      tx.executeWithoutResult(
          t -> {
            renew(owner);
            db.update(
                "update sources set"
                    + " last_fetched_at=now(),last_error=?,next_fetch_at=?,updated_at=now() where"
                    + " id=? and feed_url=? and source_type=?",
                reason,
                nextFetch == null ? null : Timestamp.from(nextFetch),
                s.id,
                s.url,
                s.type);
            finish(runId, counts, "FAILED", reason);
          });
    }
    log.info(
        "event=feed_finished source={} run={} fetched={} inserted={} duplicates={} errors={}",
        s.id,
        runId,
        counts.fetched,
        counts.inserted,
        counts.duplicates,
        counts.errors);
    return counts;
  }

  private static String safeReason(Exception ex) {
    if (ex instanceof FeedClient.FetchFailure) return ex.getMessage();
    if (ex instanceof IllegalArgumentException) return "Invalid feed format or destination";
    return "Publisher connection or import failed";
  }

  private void renew(String owner) {
    if (db.update(
            """
            update ingestion_locks set expires_at=now()+interval '3 minutes'
            where lock_name='collect' and owner_id=? and expires_at>now()
            """,
            owner)
        != 1) throw new LostLease();
  }

  private void finish(long id, Counts c, String status, String error) {
    db.update(
        """
        update ingestion_runs set finished_at=now(),status=?,fetched_count=?,inserted_count=?,
        duplicate_count=?,error_count=?,error_message=? where id=?
        """,
        status,
        c.fetched,
        c.inserted,
        c.duplicates,
        c.errors,
        error,
        id);
  }

  private Long insert(Source s, FeedParser.Story story, Picture picture) {
    var classification = Classifier.classify(story, s.name);
    String publication = classification.needsReview() ? "DRAFT" : "PUBLISHED";
    String slug = Classifier.slug(story.title()) + "-" + hash(story.canonical()).substring(0, 16);
    List<Long> ids =
        db.queryForList(
            """
            insert into articles(source_id,title,original_title,slug,original_url,canonical_url,author,
            source_description,summary,source_item_id,published_at,reading_time_minutes,processing_status,publication_status)
            values (?,?,?,?,?,?,?,?,?,?,?,1,'READY',?) on conflict do nothing returning id
            """,
            Long.class,
            s.id,
            story.title(),
            story.title(),
            slug,
            story.original(),
            story.canonical(),
            story.author(),
            story.preview(),
            story.preview(),
            story.itemId(),
            Timestamp.from(story.published()),
            publication);
    String image = picture == null ? null : picture.url();
    db.update(
        """
        update articles set source_item_id=coalesce(source_item_id,?),author=coalesce(nullif(?,''),author),
          summary=case when summary is not distinct from source_description and ?<>'' then ? else summary end,
          source_description=coalesce(nullif(?,''),source_description),
          image_url=coalesce(?,image_url),image_source=coalesce(?,image_source),
          image_alt=case when ? then ? else image_alt end,image_attribution=case when ? then ? else image_attribution end,
          image_status=case when ? then 'AVAILABLE' when image_status='AVAILABLE' and ? then image_status else ? end
        where source_id=? and id=(select id from articles where source_id=? and (source_item_id=? or canonical_url=?)
          order by (source_item_id=? ) desc nulls last limit 1)
        """,
        story.itemId(),
        story.author(),
        story.preview(),
        story.preview(),
        story.preview(),
        image,
        picture == null ? null : picture.source(),
        picture != null,
        story.title(),
        picture != null,
        s.name,
        picture != null,
        licensed(s.url),
        licensed(s.url) ? "MISSING" : "UNREVIEWED",
        s.id,
        s.id,
        story.itemId(),
        story.canonical(),
        story.itemId());
    if (ids.isEmpty()) return null;
    long id = ids.getFirst();
    classification
        .categories()
        .forEach(
            (category, confidence) ->
                db.update(
                    """
                    insert into article_categories(article_id,category_id,confidence)
                    select ?,id,? from categories where slug=? and enabled
                    """,
                    id,
                    confidence,
                    category));
    for (String topic : classification.topics()) {
      String topicSlug = Classifier.slug(topic);
      db.update(
          "insert into topics(name,slug) values (?,?) on conflict(slug) do nothing",
          topic,
          topicSlug);
      db.update(
          "insert into article_topics(article_id,topic_id) select ?,id from topics where slug=?",
          id,
          topicSlug);
    }
    if (story.advisory() != null) {
      var advisory = story.advisory();
      db.update(
          """
          insert into security_details(article_id,cve_id,affected_company,affected_product,incident_type,
          exploitation_status,mitigation,verified_at) values (?,?,?,?,'Known exploited vulnerability',
          'Confirmed by CISA',?,now())
          """,
          id,
          advisory.cve(),
          advisory.vendor(),
          advisory.product(),
          advisory.mitigation());
    } else if (classification.categories().containsKey("security")) {
      var identifiers =
          CVE.matcher(story.title() + " " + story.preview())
              .results()
              .map(m -> m.group().toUpperCase(Locale.ROOT))
              .distinct()
              .limit(2)
              .toList();
      if (identifiers.size() == 1)
        db.update(
            "insert into security_details(article_id,cve_id) values (?,?)",
            id,
            identifiers.getFirst());
    }
    return id;
  }

  private static String hash(String input) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }
}
