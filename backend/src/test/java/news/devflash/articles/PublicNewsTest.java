package news.devflash.articles;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;

class PublicNewsTest {
  @Test
  void cursorRejectsInvalidAndNegativeInputs() {
    for (String input :
        List.of(
            "00",
            "X".repeat(180),
            Base64.getUrlEncoder().encodeToString("2020-01-01T00:00:00Z|-1|NaN".getBytes())))
      assertThrows(ResponseStatusException.class, () -> NewsQueries.Position.decode(input));
    var p = new NewsQueries.Position(Instant.parse("2020-01-01T00:00:00.125600Z"), 13, 0.45);
    assertEquals(p, NewsQueries.Position.decode(p.encode()));
  }

  @Test
  @EnabledIfEnvironmentVariable(
      named = "DEVFLASH_TEST_DB_URL",
      matches = "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/devflash_test")
  void usesMigratedPostgresForSearchPaginationAndBriefings() {
    String url = System.getenv("DEVFLASH_TEST_DB_URL"),
        user = System.getenv("DEVFLASH_TEST_DB_USERNAME"),
        pw = System.getenv("DEVFLASH_TEST_DB_PASSWORD");
    String schema = "reader_" + UUID.randomUUID().toString().replace("-", "");
    var main = new JdbcTemplate(new DriverManagerDataSource(url, user, pw));
    main.execute("create schema " + schema);
    try {
      var ds = new DriverManagerDataSource(url + "?currentSchema=" + schema, user, pw);
      var flyway = Flyway.configure().dataSource(ds).schemas(schema).load();
      flyway.migrate();
      assertEquals(0, flyway.migrate().migrationsExecuted);
      var db = new JdbcTemplate(ds);
      var reader = new NewsQueries(new NamedParameterJdbcTemplate(ds));
      var briefing = new DailyBriefing(db, "Asia/Phnom_Penh");
      var api = new NewsApi(reader, db, briefing, "Asia/Phnom_Penh", "");
      db.update(
          """
          INSERT INTO articles(source_id,title,original_title,slug,original_url,canonical_url,summary,published_at,publication_status)
          SELECT 1,'Language tooling '||i,'Language tooling '||i,'fixture-'||i,'https://example.org/'||i,
            'https://example.org/'||i,'Compiler update',now(),CASE WHEN i=4 THEN 'DRAFT' ELSE 'PUBLISHED' END
          FROM generate_series(1,4) i
          """);
      db.update(
          "INSERT INTO article_categories SELECT id,(SELECT id FROM categories WHERE"
              + " slug='developer'),1 FROM articles WHERE id<=2");
      db.update("INSERT INTO topics(name,slug) VALUES('Runtime','runtime')");
      db.update("INSERT INTO article_topics VALUES(1,1)");
      assertEquals(0L, reader.one("fixture-1").viewCount());
      var viewer = new MockHttpSession();
      api.event("fixture-1", new NewsApi.Event("view"), viewer);
      api.event("fixture-1", new NewsApi.Event("view"), viewer);
      api.event("fixture-1", new NewsApi.Event("share"), viewer);
      api.event("fixture-1", new NewsApi.Event("view"), new MockHttpSession());
      assertEquals(2L, reader.one("fixture-1").viewCount());
      assertEquals(0L, reader.one("fixture-2").viewCount());
      assertEquals(
          2L,
          api.feed(null, null, "runtime", null, null, "newest", 10, null)
              .data()
              .getFirst()
              .viewCount());
      var first = api.feed(null, null, "", null, null, "newest", 2, null);
      assertEquals(List.of(3L, 2L), first.data().stream().map(NewsQueries.Story::id).toList());
      assertEquals(
          1,
          api.feed(null, null, "", null, null, "newest", 2, first.nextCursor())
              .data()
              .getFirst()
              .id());
      assertEquals(
          2,
          api.feed("developer", null, "compiler", null, null, "relevance", 10, null).data().size());
      assertEquals(
          1, api.feed(null, null, "runtime", null, null, "relevance", 10, null).data().size());
      assertEquals(
          3, api.feed(null, null, "Kubernetes", null, null, "newest", 10, null).data().size());
      assertEquals(
          0,
          api.feed(null, null, "", null, LocalDate.of(2000, 1, 1), "newest", 10, null)
              .data()
              .size());
      assertThrows(ResponseStatusException.class, () -> reader.one("fixture-4"));
      assertEquals(1, reader.related(reader.one("fixture-1")).size());
      var personalized =
          api.feed(null, null, "", null, null, "newest", 1, null, "for-you", "runtime", null);
      assertEquals(1L, personalized.data().getFirst().id());
      assertEquals(
          3L,
          api.feed(
                  null,
                  null,
                  "",
                  null,
                  null,
                  "newest",
                  1,
                  personalized.nextCursor(),
                  "for-you",
                  "runtime",
                  null)
              .data()
              .getFirst()
              .id());
      assertEquals(
          3L,
          api.feed(null, null, "", null, null, "newest", 1, null, "latest", null, null)
              .data()
              .getFirst()
              .id());
      assertThrows(
          ResponseStatusException.class,
          () ->
              api.feed(null, null, "", null, null, "newest", 1, null, "for-you", "runtime,", null));

      db.update("INSERT INTO article_categories SELECT 2,id FROM categories WHERE slug='security'");
      db.update(
          """
          INSERT INTO security_details(article_id,cve_id,severity,affected_product,patch_status,verification_status)
          VALUES (2,'CVE-2020-1234','High','Fixture product','Available','Vulnerability disclosed')
          """);
      var security =
          api.security(
              "high",
              null,
              "fixture",
              "cve-2020-1234",
              "available",
              "Vulnerability disclosed",
              null,
              null,
              10,
              null);
      assertEquals(1, ((List<?>) security.get("data")).size());
      assertEquals(
          List.of(),
          api.security("critical", null, null, null, null, null, null, null, 10, null).get("data"));
      assertTrue(((Map<?, ?>) security.get("security")).containsKey("fixture-2"));

      db.update(
          """
          INSERT INTO article_enrichments(article_id,what_happened,why_it_matters,key_takeaway,generation_status)
          VALUES (1,'A sourced change','Practical impact','A supported point','GENERATED')
          """);
      assertNotNull(((Map<?, ?>) api.article("fixture-1").get("data")).get("enrichment"));
      assertNull(((Map<?, ?>) api.article("fixture-2").get("data")).get("enrichment"));
      briefing.generate(briefing.today());
      briefing.generate(briefing.today());
      assertEquals(3, briefing.edition(briefing.today()).articles().size());
      assertEquals(List.of(briefing.today()), briefing.archive());
      assertEquals(
          "A supported point", briefing.edition(briefing.today()).notes().get(1L).keyTakeaway());
      db.update("UPDATE articles SET title='New story' WHERE id=3");
      assertEquals(3, briefing.edition(briefing.today()).articles().size());
      assertEquals(List.of(), briefing.edition(briefing.today().minusDays(2)).articles());
      var stats = (Map<?, ?>) api.stats().get("data");
      assertEquals(3L, stats.get("total"));
      assertEquals(3, stats.get("minutes"));
      assertFalse(((List<?>) api.trending().get("data")).isEmpty());
      assertThrows(
          ResponseStatusException.class,
          () -> api.feed(null, null, "", null, null, "other", 99, null));
    } finally {
      main.execute("drop schema " + schema + " cascade");
    }
  }
}
