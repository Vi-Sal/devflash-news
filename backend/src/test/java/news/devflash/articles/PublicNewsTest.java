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
      var api = new NewsApi(reader, db, "Asia/Phnom_Penh", "");
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
