package news.devflash.admin;

import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import news.devflash.ingestion.FeedClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminEdits {
  record ArticleInput(
      @Size(min = 1, max = 500) String title,
      @Size(max = 8000) String summary,
      @Pattern(regexp = "PUBLISHED|DRAFT") String publicationStatus,
      Boolean featured,
      @Size(max = 50) List<@NotNull @Positive Long> categoryIds,
      @Size(max = 100) List<@NotNull @Positive Long> topicIds) {}

  record SourceInput(
      @Size(min = 1, max = 160) String name,
      @Size(max = 2048) String websiteUrl,
      @Size(max = 2048) String feedUrl,
      @Pattern(regexp = "RSS|ATOM|JSON_FEED|CISA_KEV") String sourceType,
      Boolean enabled) {}

  record TaxonomyInput(
      @Size(min = 1, max = 120) String name,
      @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 100) String slug,
      @Size(max = 1200) String description,
      @Pattern(regexp = "[A-Za-z][A-Za-z0-9-]{0,49}") String icon,
      @Pattern(regexp = "#[0-9a-fA-F]{6}") String accentColor,
      Boolean enabled) {}

  private final JdbcTemplate db;
  private final AdminAudit audit;
  private final FeedClient feeds;

  AdminEdits(JdbcTemplate db, AdminAudit audit, FeedClient feeds) {
    this.db = db;
    this.audit = audit;
    this.feeds = feeds;
  }

  @Transactional
  public void article(String user, long id, ArticleInput input) {
    if (input.title() != null && input.title().isBlank()) throw invalid();
    requireRow(
        db.update(
            """
            update articles set title=coalesce(?,title),summary=coalesce(?,summary),
            publication_status=coalesce(?,publication_status),featured=coalesce(?,featured),updated_at=now() where id=? and processing_status<>'DUPLICATE'
            """,
            input.title(),
            input.summary(),
            input.publicationStatus(),
            input.featured(),
            id));
    if (input.categoryIds() != null)
      assign("article_categories", "category_id", id, input.categoryIds());
    if (input.topicIds() != null) assign("article_topics", "topic_id", id, input.topicIds());
    audit.record(user, "edit", "article", id);
  }

  private void assign(String joinTable, String key, long articleId, List<Long> values) {
    db.update("delete from " + joinTable + " where article_id=?", articleId);
    for (Long value : new LinkedHashSet<>(values))
      db.update(
          "insert into " + joinTable + "(article_id," + key + ") values(?,?)", articleId, value);
  }

  @Transactional
  public void removeDuplicate(String user, long id) {
    requireRow(
        db.update(
            """
            update articles set publication_status='DRAFT',processing_status='DUPLICATE',featured=false,updated_at=now()
            where id=? and processing_status<>'DUPLICATE'
            """,
            id));
    audit.record(user, "remove_duplicate", "article", id);
  }

  @Transactional
  public long source(String user, Long id, SourceInput input) {
    if (input.name() != null && input.name().isBlank()) throw invalid();
    if (input.feedUrl() != null) feeds.validate(input.feedUrl());
    if (input.websiteUrl() != null) https(input.websiteUrl());
    boolean create = id == null;
    if (create) {
      if (input.feedUrl() == null || input.websiteUrl() == null || input.name() == null)
        throw invalid();
      id =
          db.queryForObject(
              """
              insert into sources(name,website_url,feed_url,source_type,enabled) values(?,?,?,?,?) returning id
              """,
              Long.class,
              input.name(),
              input.websiteUrl(),
              input.feedUrl(),
              input.sourceType() == null ? "RSS" : input.sourceType(),
              Boolean.TRUE.equals(input.enabled()));
    } else {
      requireRow(
          db.update(
              """
              update sources set name=coalesce(?,name),website_url=coalesce(?,website_url),
                  etag=case when feed_url<>coalesce(?,feed_url) then null else etag end,
                  last_modified=case when feed_url<>coalesce(?,feed_url) then null else last_modified end,
                  next_fetch_at=case when feed_url<>coalesce(?,feed_url) then null else next_fetch_at end,
                  feed_url=coalesce(?,feed_url),source_type=coalesce(?,source_type),enabled=coalesce(?,enabled),updated_at=now()
              where id=?
              """,
              input.name(),
              input.websiteUrl(),
              input.feedUrl(),
              input.feedUrl(),
              input.feedUrl(),
              input.feedUrl(),
              input.sourceType(),
              input.enabled(),
              id));
    }
    audit.record(user, create ? "create" : "edit", "source", id);
    return id;
  }

  @Transactional
  public long taxonomy(String user, Long id, TaxonomyInput input, boolean category) {
    if (input.name() != null && input.name().isBlank()) throw invalid();
    String table = category ? "categories" : "topics";
    boolean create = id == null;
    if (create) {
      if (input.name() == null || input.slug() == null) throw invalid();
      if (category)
        id =
            db.queryForObject(
                """
                insert into categories(name,slug,description,icon,accent_color,enabled) values(?,?,?,?,?,?) returning id
                """,
                Long.class,
                input.name(),
                input.slug(),
                input.description() == null ? "" : input.description(),
                input.icon() == null ? "Layers" : input.icon(),
                input.accentColor() == null ? "#2583FF" : input.accentColor(),
                !Boolean.FALSE.equals(input.enabled()));
      else
        id =
            db.queryForObject(
                "insert into topics(name,slug,description) values(?,?,?) returning id",
                Long.class,
                input.name(),
                input.slug(),
                input.description() == null ? "" : input.description());
    } else {
      var params = new ArrayList<Object>();
      params.add(input.name());
      params.add(input.slug());
      params.add(input.description());
      String sql =
          "update "
              + table
              + " set name=coalesce(?,name),slug=coalesce(?,slug),description=coalesce(?,description)";
      if (category) {
        sql +=
            ",icon=coalesce(?,icon),accent_color=coalesce(?,accent_color),enabled=coalesce(?,enabled)";
        params.add(input.icon());
        params.add(input.accentColor());
        params.add(input.enabled());
      }
      params.add(id);
      requireRow(db.update(sql + " where id=?", params.toArray()));
    }
    audit.record(user, create ? "create" : "edit", category ? "category" : "topic", id);
    return id;
  }

  @Transactional
  public void mergeTopics(String user, long source, long destination) {
    if (source == destination || source < 1 || destination < 1) throw invalid();
    var locked =
        db.queryForList(
            "select id from topics where id in (?,?) order by id for update",
            Long.class,
            source,
            destination);
    if (locked.size() != 2) throw new Missing();
    db.update(
        """
        insert into article_topics(article_id,topic_id) select article_id,? from article_topics where topic_id=?
        on conflict do nothing
        """,
        destination,
        source);
    db.update("delete from topics where id=?", source);
    audit.record(user, "merge_into_" + destination, "topic", source);
  }

  @Transactional
  public void settings(String user, Map<String, String> values) {
    if (values.isEmpty() || values.size() > 8) throw invalid();
    values.forEach(
        (key, value) -> {
          if (!validSetting(key, value))
            throw new IllegalArgumentException("Invalid value for site setting");
          db.update(
              """
              insert into site_settings(setting_key,setting_value,updated_at) values(?,?,now())
              on conflict(setting_key) do update set setting_value=excluded.setting_value,updated_at=now()
              """,
              key,
              value);
        });
    audit.record(user, "update", "settings", null);
  }

  static boolean validSetting(String key, String value) {
    if (value == null || value.length() > 2048) return false;
    return switch (key) {
      case "siteTitle" -> !value.isBlank() && value.length() <= 100;
      case "tagline" -> !value.isBlank() && value.length() <= 220;
      case "youtubeUrl" -> {
        if (value.isEmpty()) yield true;
        URI url = https(value);
        yield Set.of("youtube.com", "www.youtube.com")
                .contains(url.getHost().toLowerCase(java.util.Locale.ROOT))
            && url.getPath().matches("/(@[\\w.-]+|(?:channel|c|user)/[\\w.-]+)/?");
      }
      case "ingestionBatchLimit" -> integerBetween(value, 1, 100);
      case "defaultPageSize" -> integerBetween(value, 1, 30);
      case "aiEnabled" -> value.equals("true") || value.equals("false");
      case "aiDailyBudget" -> integerBetween(value, 0, 1000);
      case "trendingWindowDays" -> integerBetween(value, 1, 90);
      default -> false;
    };
  }

  private static boolean integerBetween(String value, int min, int max) {
    if (!value.matches("[0-9]{1,5}")) return false;
    int n = Integer.parseInt(value);
    return n >= min && n <= max;
  }

  private static URI https(String value) {
    try {
      URI url = URI.create(value);
      if ("https".equalsIgnoreCase(url.getScheme())
          && url.getHost() != null
          && url.getUserInfo() == null
          && (url.getPort() == 443 || url.getPort() == -1)) return url;
    } catch (IllegalArgumentException ignored) {
      /* Emit the same safe validation error for all malformed URLs. */
    }
    throw new IllegalArgumentException("Enter a valid HTTPS URL");
  }

  static class Missing extends RuntimeException {}

  private static void requireRow(int changed) {
    if (changed == 0) throw new Missing();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid administrative input");
  }
}
