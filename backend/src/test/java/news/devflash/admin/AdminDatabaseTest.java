package news.devflash.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "DEVFLASH_ADMIN_TEST_DB", matches = "true")
class AdminDatabaseTest {
  private static final String SCHEMA = "df_admin_" + UUID.randomUUID().toString().replace("-", "");
  private static final String USER = "integration-editor";
  private static final String SECRET = UUID.randomUUID().toString();
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate db;
  private MockHttpSession session;
  private String csrf;

  static String testUrl() {
    String url = System.getenv("DB_URL");
    if (url == null || !url.startsWith("jdbc:postgresql://"))
      throw new IllegalStateException("A local test database is required");
    URI parsed = URI.create(url.substring(5));
    if (!java.util.Set.of("localhost", "127.0.0.1").contains(parsed.getHost())
        || !parsed.getPath().endsWith("_test"))
      throw new IllegalStateException("Admin tests require a loopback database ending in _test");
    return url;
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry props) {
    String url = testUrl();
    props.add(
        "spring.datasource.url",
        () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
    props.add("spring.flyway.default-schema", () -> SCHEMA);
    props.add("ADMIN_BOOTSTRAP_USERNAME", () -> USER);
    props.add("ADMIN_BOOTSTRAP_PASSWORD", () -> SECRET);
  }

  @AfterAll
  static void cleanOwnSchema() throws Exception {
    try (var connection =
            DriverManager.getConnection(
                testUrl(), System.getenv("DB_USERNAME"), System.getenv("DB_PASSWORD"));
        var statement = connection.createStatement()) {
      statement.execute("drop schema if exists " + SCHEMA + " cascade");
    }
  }

  private String token() throws Exception {
    var req = get("/api/v1/admin/auth/csrf");
    if (session != null) req.session(session);
    var response = mvc.perform(req).andExpect(status().isOk()).andReturn();
    session = (MockHttpSession) response.getRequest().getSession();
    return mapper.readTree(response.getResponse().getContentAsString()).path("token").asText();
  }

  private JsonNode mutate(MockHttpServletRequestBuilder req, Object data, int status)
      throws Exception {
    var result =
        mvc.perform(
                req.session(session)
                    .header("X-CSRF-TOKEN", csrf)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsBytes(data)))
            .andExpect(status().is(status))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return result.isBlank() ? null : mapper.readTree(result).get("data");
  }

  @Test
  void realSessionSupportsModerationSettingsTaxonomyAuditAndLogout() throws Exception {
    String firstToken = token();
    String hash =
        db.queryForObject(
            "select password_hash from admin_users where username=?", String.class, USER);
    assertThat(hash).startsWith("$2");
    mvc.perform(
            post("/api/v1/admin/auth/login")
                .session(session)
                .header("X-CSRF-TOKEN", firstToken)
                .param("username", USER)
                .param("password", SECRET))
        .andExpect(status().isNoContent());
    csrf = token();
    mvc.perform(get("/api/v1/admin/dashboard").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.active_sources").isNumber())
        .andExpect(jsonPath("$.data.distribution").isArray());
    long source =
        mutate(
                post("/api/v1/admin/sources"),
                Map.of(
                    "name",
                    "Fixture publisher",
                    "websiteUrl",
                    "https://go.dev",
                    "feedUrl",
                    "https://go.dev/example/test.xml",
                    "sourceType",
                    "ATOM"),
                201)
            .asLong();
    long category =
        mutate(
                post("/api/v1/admin/categories"),
                Map.of("name", "Fixture category", "slug", "fixture-category"),
                201)
            .asLong();
    long topic =
        mutate(
                post("/api/v1/admin/topics"),
                Map.of("name", "Fixture topic", "slug", "fixture-topic"),
                201)
            .asLong();
    long target =
        mutate(
                post("/api/v1/admin/topics"),
                Map.of("name", "Fixture destination", "slug", "fixture-target"),
                201)
            .asLong();
    long id =
        db.queryForObject(
            """
            insert into articles(source_id,original_title,title,slug,original_url,canonical_url,published_at)
            values(?,'Original','Original','test-story','https://example.org/story','https://example.org/story',now()) returning id
            """,
            Long.class,
            source);
    mutate(
        patch("/api/v1/admin/articles/" + id),
        Map.of(
            "title",
            "Edited headline",
            "summary",
            "Edited summary",
            "categoryIds",
            new long[] {category},
            "topicIds",
            new long[] {topic},
            "featured",
            true,
            "publicationStatus",
            "PUBLISHED"),
        200);
    mutate(post("/api/v1/admin/topics/" + topic + "/merge"), Map.of("destinationId", target), 200);
    mvc.perform(get("/api/v1/admin/articles").param("q", "Edited").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].original_title").value("Original"))
        .andExpect(jsonPath("$.data[0].topic_ids").value(String.valueOf(target)));
    mutate(
        patch("/api/v1/admin/settings"),
        Map.of("siteTitle", "Integration newsroom", "aiDailyBudget", "0"),
        200);
    mutate(delete("/api/v1/admin/articles/" + id), Map.of(), 204);
    assertThat(
            db.queryForObject(
                "select processing_status from articles where id=?", String.class, id))
        .isEqualTo("DUPLICATE");
    mvc.perform(get("/api/v1/admin/audit-logs").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].action").value("remove_duplicate"));
    mvc.perform(post("/api/v1/admin/auth/logout").session(session).header("X-CSRF-TOKEN", csrf))
        .andExpect(status().isNoContent());
    assertThat(session.isInvalid()).isTrue();
  }
}
