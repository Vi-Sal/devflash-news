package news.devflash.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import news.devflash.summarization.ArticleSummarizationService;
import news.devflash.summarization.EnrichmentService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "DEVFLASH_TEST_DB_URL", matches = ".+")
class IngestionDatabaseTest {
  private JdbcTemplate bootstrap;
  private JdbcTemplate db;
  private String schema;
  private TransactionTemplate tx;
  private FeedClient client;
  private IngestionService service;
  private static final String KUBE = "https://kubernetes.io/feed.xml";
  private static final String GO = "https://go.dev/blog/feed.atom";

  @BeforeEach
  void database() {
    String url = System.getenv("DEVFLASH_TEST_DB_URL");
    var uri = URI.create(url.substring(5));
    assertThat(uri.getHost()).isIn("localhost", "127.0.0.1", "[::1]");
    assertThat(uri.getPath()).endsWith("_test");
    assertThat(uri.getQuery()).isNull();
    String user = System.getenv("DEVFLASH_TEST_DB_USERNAME"),
        pass = System.getenv("DEVFLASH_TEST_DB_PASSWORD");
    bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, user, pass));
    schema = "feedtest_" + UUID.randomUUID().toString().replace("-", "");
    bootstrap.execute("create schema " + schema);
    var ds = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, pass);
    db = new JdbcTemplate(ds);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    Flyway.configure().dataSource(ds).schemas(schema).load().migrate();
    client = mock(FeedClient.class);
    db.update("UPDATE sources SET enabled=false WHERE source_type='CISA_KEV'");
    service = newService(client);
  }

  private IngestionService newService(FeedClient fetcher) {
    return new IngestionService(
        db,
        tx,
        fetcher,
        new FeedParser(new ObjectMapper()),
        mock(EnrichmentService.class),
        mock(news.devflash.articles.DailyBriefing.class));
  }

  @AfterEach
  void removeTestSchema() {
    if (schema != null) bootstrap.execute("drop schema if exists " + schema + " cascade");
  }

  @Test
  void failureIsIsolatedAndFailedSourceCanRecoverWithoutRepeatingStories() throws Exception {
    var feed = fixture();
    when(client.fetch(KUBE, null, null))
        .thenThrow(new FeedClient.FetchFailure("Publisher connection failed", null))
        .thenReturn(feed);
    when(client.fetch(GO, null, null)).thenReturn(feed);
    var first = service.run(null);
    assertThat(first.inserted()).isEqualTo(2);
    assertThat(first.errors()).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from ingestion_runs where status='FAILED'", Integer.class))
        .isEqualTo(1);
    when(client.fetch(GO, "fixture-tag", null))
        .thenReturn(new FeedClient.Payload(304, new byte[0], null, null, null, null));
    var second = service.run(null);
    assertThat(second.inserted()).isZero();
    assertThat(second.errors()).isZero();
    assertThat(second.duplicates()).isEqualTo(2);
    assertThat(db.queryForObject("select count(*) from articles", Integer.class)).isEqualTo(2);
    assertThat(
            db.queryForObject(
                "select count(*) from sources where last_success_at is not null and last_error is"
                    + " null",
                Integer.class))
        .isEqualTo(2);
  }

  @Test
  void backlogProcessesLaterEntriesBeforeAdvancingHttpValidators() throws Exception {
    db.update(
        "insert into site_settings(setting_key,setting_value) values ('ingestionBatchLimit','1')");
    when(client.fetch(KUBE, null, null)).thenReturn(fixture());
    long id = source(KUBE);
    assertThat(service.run(id).inserted()).isEqualTo(1);
    assertThat(db.queryForObject("select etag from sources where id=?", String.class, id)).isNull();
    assertThat(service.run(id).inserted()).isEqualTo(1);
    assertThat(db.queryForObject("select etag from sources where id=?", String.class, id))
        .isEqualTo("fixture-tag");
  }

  @Test
  void overlappingWorkerIsRejectedAndExpiredLeaseRecovers() throws Exception {
    long id = source(KUBE);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(client.fetch(KUBE, null, null))
        .thenAnswer(
            call -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Release signal timed out");
              return fixture();
            });
    var workers = Executors.newSingleThreadExecutor();
    try {
      var running = workers.submit(() -> service.run(id));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> service.run(id))
          .isInstanceOfSatisfying(
              ResponseStatusException.class,
              ex -> assertThat(ex.getStatusCode().value()).isEqualTo(409));
      release.countDown();
      assertThat(running.get(8, TimeUnit.SECONDS).inserted()).isEqualTo(2);
    } finally {
      release.countDown();
      workers.shutdownNow();
    }
    db.update(
        "insert into ingestion_locks values ('collect','prior-worker',now()-interval '1 second')");
    db.update("insert into ingestion_runs(source_id,status) values (?,'RUNNING')", id);
    when(client.fetch(KUBE, "fixture-tag", null))
        .thenReturn(new FeedClient.Payload(304, new byte[0], null, null, null, null));
    assertThat(service.run(id).errors()).isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from ingestion_runs where status='FAILED'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void optionalProviderUsesEvidenceAndBoundedBudget() throws Exception {
    when(client.fetch(KUBE, null, null)).thenReturn(fixture());
    service.run(source(KUBE));
    var ids = db.queryForList("select id from articles order by id", Long.class);
    db.update("update articles set source_description=repeat('Source-provided evidence. ',8)");
    db.update(
        "insert into site_settings(setting_key,setting_value) values"
            + " ('aiEnabled','true'),('aiDailyBudget','1')");
    var adapter = mock(ArticleSummarizationService.class);
    var enrich = new EnrichmentService(db, tx, adapter, "Asia/Phnom_Penh");
    assertThat(enrich.regenerate(ids.getFirst())).isEqualTo("PREVIEW");
    when(adapter.available()).thenReturn(true);
    when(adapter.provider()).thenReturn("Fixture");
    when(adapter.model()).thenReturn("Fixture model");
    when(adapter.summarize(any()))
        .thenThrow(new java.io.IOException("Fixture failure"))
        .thenReturn(
            new ArticleSummarizationService.Result(
                Optional.of(
                    new ArticleSummarizationService.Explanation("Fixture brief", null, null, null)),
                120,
                35));
    assertThat(enrich.regenerate(ids.getFirst())).isEqualTo("FAILED");
    assertThat(enrich.regenerate(ids.getLast())).isEqualTo("DAILY_LIMIT");
    assertThat(db.queryForObject("select sum(calls) from ai_usage", Integer.class)).isEqualTo(1);
    db.update("update site_settings set setting_value='5' where setting_key='aiDailyBudget'");
    assertThat(enrich.regenerate(ids.getFirst())).isEqualTo("GENERATED");
    assertThat(enrich.regenerate(ids.getFirst())).isEqualTo("GENERATED");
    assertThat(db.queryForObject("select sum(input_tokens) from ai_usage", Long.class))
        .isEqualTo(120);
    assertThat(db.queryForObject("select sum(output_tokens) from ai_usage", Long.class))
        .isEqualTo(35);
    verify(adapter, times(2)).summarize(any());
    assertThat(db.queryForObject("select what_happened from article_enrichments", String.class))
        .isEqualTo("Fixture brief");
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "DEVFLASH_TEST_LIVE_FEEDS", matches = "true")
  void verifiedPublisherFeedsImportFromTheNetworkAndRepeatSafely() throws Exception {
    db.update(
        "insert into site_settings(setting_key,setting_value) values"
            + " ('ingestionBatchLimit','100')");
    var liveClient = new FeedClient("go.dev,kubernetes.io");
    try {
      var live = newService(liveClient);
      var first = live.run(null);
      assertThat(first.errors()).isZero();
      assertThat(first.inserted()).isGreaterThan(0);
      assertThat(db.queryForObject("select count(distinct source_id) from articles", Integer.class))
          .isEqualTo(2);
      var second = live.run(null);
      assertThat(second.errors()).isZero();
      assertThat(second.inserted()).isZero();
      long advisoryId =
          db.queryForObject(
              """
              insert into sources(name,website_url,feed_url,source_type,enabled)
              values ('CISA KEV','https://www.cisa.gov/known-exploited-vulnerabilities-catalog',?,'CISA_KEV',true)
              on conflict(feed_url) do update set enabled=true returning id
              """,
              Long.class,
              FeedClient.KEV_URL);
      var test = live.test(advisoryId);
      var advisories = live.run(advisoryId);
      assertThat(advisories.errors()).isZero();
      assertThat(live.run(advisoryId).inserted()).isZero();
      if (test.entries() > 0)
        assertThat(
                db.queryForObject(
                    """
                    select count(*) from security_details d join articles a on a.id=d.article_id where
                    a.source_id=? and d.cve_id is not null and d.exploitation_status='Confirmed by CISA'
                    """,
                    Integer.class,
                    advisoryId))
            .isPositive();
    } finally {
      liveClient.close();
    }
  }

  @Test
  void stableIdentifierRefreshesLicensedMediaAndKeepsEditorialFields() throws Exception {
    org.springframework.test.util.ReflectionTestUtils.setField(service, "licensedHosts", "go.dev");
    String xml =
        """
        <rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Fixture</title>
        <link>https://go.dev</link><description>Fixture</description><item><guid isPermaLink="false">stable-id</guid>
        <title>Go developer news</title><link>https://go.dev/first</link><description>First preview</description>
        <pubDate>Tue, 10 Nov 2020 00:00:00 GMT</pubDate><media:thumbnail url="https://go.dev/thumb.jpg"/>
        <media:content type="image/jpeg" url="https://go.dev/photo.jpg"/></item></channel></rss>
        """;
    String changed =
        xml.replace("/first", "/second")
            .replace("photo.jpg", "updated.jpg")
            .replace("First preview", "Updated preview");
    when(client.fetch(GO, null, null))
        .thenReturn(
            new FeedClient.Payload(
                200, xml.getBytes(StandardCharsets.UTF_8), null, null, null, null),
            new FeedClient.Payload(
                200, changed.getBytes(StandardCharsets.UTF_8), null, null, null, null));
    assertThat(service.run(source(GO)).inserted()).isEqualTo(1);
    assertThat(db.queryForObject("select image_url from articles", String.class))
        .endsWith("/photo.jpg");
    db.update("update articles set summary='Editorial text'");
    assertThat(service.run(source(GO)).duplicates()).isEqualTo(1);
    var row =
        db.queryForMap(
            "select image_url,image_status,image_source,summary,source_description from articles");
    assertThat(row)
        .containsEntry("image_url", "https://go.dev/updated.jpg")
        .containsEntry("image_status", "AVAILABLE")
        .containsEntry("image_source", "MEDIA_CONTENT")
        .containsEntry("summary", "Editorial text")
        .containsEntry("source_description", "Updated preview");
  }

  private long source(String url) {
    return db.queryForObject("select id from sources where feed_url=?", Long.class, url);
  }

  private FeedClient.Payload fixture() {
    String item =
        "<item><title>Fixture API update %d</title><link>https://example.org/item/%d</link>"
            + "<pubDate>Tue, 10 Nov 2020 00:00:00 GMT</pubDate></item>";
    byte[] body =
        ("<rss version='2.0'><channel><title>Publisher</title><link>https://example.org</link><description>Test</description>"
                + item.formatted(1, 1)
                + item.formatted(2, 2)
                + "</channel></rss>")
            .getBytes(StandardCharsets.UTF_8);
    return new FeedClient.Payload(200, body, "fixture-tag", null, null, null);
  }
}
