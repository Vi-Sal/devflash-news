package news.devflash.articles;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class NewsQueries {
  public record Story(
      long id,
      String slug,
      String title,
      String summary,
      String originalUrl,
      String imageUrl,
      String imageSource,
      String imageAlt,
      String imageAttribution,
      String imageStatus,
      String source,
      String author,
      Instant publishedAt,
      int minutes,
      boolean featured,
      long viewCount,
      List<String> categories,
      List<String> topics) {}

  public record Page(List<Story> data, String nextCursor, boolean hasMore) {}

  record Position(Instant time, long id, double rank) {
    String encode() {
      String raw = time + "|" + id + "|" + rank;
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    static Position decode(String token) {
      try {
        if (token.length() > 160) throw new IllegalArgumentException();
        String[] p =
            new String(Base64.getUrlDecoder().decode(token), StandardCharsets.US_ASCII)
                .split("\\|", -1);
        if (p.length != 3) throw new IllegalArgumentException();
        var value =
            new Position(Instant.parse(p[0]), Long.parseLong(p[1]), Double.parseDouble(p[2]));
        if (value.id <= 0 || !Double.isFinite(value.rank) || value.rank < 0)
          throw new IllegalArgumentException();
        return value;
      } catch (RuntimeException ex) {
        throw bad("Invalid page cursor");
      }
    }
  }

  static final String COLUMNS =
      """
      a.id,a.slug,a.title,a.summary,a.original_url,case when a.image_status='AVAILABLE' then a.image_url end as image_url,a.image_source,a.image_alt,a.image_attribution,a.image_status,s.name AS source,a.author,a.published_at,
      a.reading_time_minutes,a.featured,COALESCE(m.view_count,0) AS view_count,
      ARRAY(SELECT c.slug FROM article_categories ac JOIN categories c ON c.id=ac.category_id
              WHERE ac.article_id=a.id AND c.enabled ORDER BY ac.confidence DESC,c.id) categories,
      ARRAY(SELECT t.slug FROM article_topics atp JOIN topics t ON t.id=atp.topic_id
              WHERE atp.article_id=a.id ORDER BY t.name) topics
      """;
  static final String BASE =
      " FROM articles a JOIN sources s ON a.source_id=s.id LEFT JOIN article_metrics m ON"
          + " m.article_id=a.id WHERE a.publication_status='PUBLISHED' ";
  final NamedParameterJdbcTemplate db;

  public NewsQueries(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  static void validSlug(String value) {
    if (value != null && !value.matches("[a-z0-9][a-z0-9-]{0,249}")) throw bad("Invalid slug");
  }

  static Story story(ResultSet r, int row) throws SQLException {
    return new Story(
        r.getLong("id"),
        r.getString("slug"),
        r.getString("title"),
        r.getString("summary"),
        r.getString("original_url"),
        r.getString("image_url"),
        r.getString("image_source"),
        r.getString("image_alt"),
        r.getString("image_attribution"),
        r.getString("image_status"),
        r.getString("source"),
        r.getString("author"),
        r.getTimestamp("published_at").toInstant(),
        r.getInt("reading_time_minutes"),
        r.getBoolean("featured"),
        r.getLong("view_count"),
        List.of((String[]) r.getArray("categories").getArray()),
        List.of((String[]) r.getArray("topics").getArray()));
  }

  public Page feed(
      String category,
      String topic,
      String query,
      LocalDate from,
      LocalDate to,
      String sort,
      int limit,
      String cursor,
      ZoneId zone) {
    return feed(
        category, topic, query, from, to, sort, limit, cursor, zone, null, List.of(), null, null);
  }

  record SecurityFilter(
      String severity,
      String incidentType,
      String affected,
      String cve,
      String patch,
      String verification) {}

  Page feed(
      String category,
      String topic,
      String query,
      LocalDate from,
      LocalDate to,
      String sort,
      int limit,
      String cursor,
      ZoneId zone,
      String mode,
      List<String> followed,
      Long exclude,
      SecurityFilter security) {
    validSlug(category);
    validSlug(topic);
    if (limit < 1
        || limit > 30
        || query.length() > 160
        || !Set.of("newest", "relevance").contains(sort)
        || (from != null && to != null && to.isBefore(from))) throw bad("Invalid feed parameters");
    StringBuilder where = new StringBuilder(BASE);
    var args = new HashMap<String, Object>();
    if (category != null) {
      where.append(
          " AND EXISTS(SELECT 1 FROM article_categories ac JOIN categories c ON c.id=ac.category_id"
              + " WHERE ac.article_id=a.id AND c.enabled AND c.slug=:category)");
      args.put("category", category);
    }
    if (topic != null) {
      where.append(
          " AND EXISTS(SELECT 1 FROM article_topics atp JOIN topics t ON t.id=atp.topic_id WHERE"
              + " atp.article_id=a.id AND t.slug=:topic)");
      args.put("topic", topic);
    }
    if (from != null) {
      where.append(" AND a.published_at>=:start");
      args.put("start", from.atStartOfDay(zone).toOffsetDateTime());
    }
    if (to != null) {
      where.append(" AND a.published_at<:end");
      args.put("end", to.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    }
    if (exclude != null) {
      if (exclude < 1) throw bad("Invalid excluded article");
      where.append(" AND a.id<>:excluded");
      args.put("excluded", exclude);
    }
    if (security != null) {
      int at = where.indexOf(" WHERE");
      where.insert(at, " LEFT JOIN security_details sd ON sd.article_id=a.id");
      appendSecurity(where, args, security);
    }
    if (mode != null && !Set.of("for-you", "latest").contains(mode)) throw bad("Invalid feed");
    if (followed.size() > 12) throw bad("Follow up to 12 topics");
    followed.forEach(NewsQueries::validSlug);
    String rank = "0.0::double precision";
    if (!query.isBlank()) {
      args.put("q", query.strip());
      String search = "websearch_to_tsquery('english',:q)";
      where.append(
          " AND (a.search_vector @@ "
              + search
              + " OR to_tsvector('english',s.name) @@ "
              + search
              + " OR EXISTS(SELECT 1 FROM article_topics atp JOIN topics t ON t.id=atp.topic_id"
              + " WHERE atp.article_id=a.id AND to_tsvector('english',t.name) @@ "
              + search
              + "))");
      if (sort.equals("relevance"))
        rank = "ts_rank(a.search_vector," + search + ")::double precision";
    }
    if ("for-you".equals(mode)) {
      // A bounded time boost keeps preferences from displacing newer editorial coverage
      // indefinitely.
      rank = "(extract(epoch FROM a.published_at) + CASE WHEN a.featured THEN 21600 ELSE 0 END";
      if (!followed.isEmpty()) {
        args.put("following", followed);
        rank +=
            " + CASE WHEN EXISTS(SELECT 1 FROM article_topics f JOIN topics t ON t.id=f.topic_id"
                + " WHERE f.article_id=a.id AND t.slug IN (:following)) THEN 43200 ELSE 0 END";
      }
      rank += ")::double precision";
    }
    String paging = "";
    if (cursor != null) {
      Position p = Position.decode(cursor);
      args.put("time", p.time.atOffset(ZoneOffset.UTC));
      args.put("id", p.id);
      args.put("rank", p.rank);
      paging = " WHERE (score,published_at,id)<(:rank,:time,:id)";
    }
    args.put("limit", limit + 1);
    String sql =
        "SELECT * FROM (SELECT "
            + COLUMNS
            + ", "
            + rank
            + " score"
            + where
            + ") ranked"
            + paging
            + " ORDER BY score DESC,published_at DESC,id DESC LIMIT :limit";
    record Hit(Story story, double rank) {}
    var hits = db.query(sql, args, (r, n) -> new Hit(story(r, n), r.getDouble("score")));
    var rows = hits.stream().limit(limit).map(Hit::story).toList();
    boolean more = hits.size() > limit;
    String next =
        more
            ? new Position(
                    rows.getLast().publishedAt(), rows.getLast().id(), hits.get(limit - 1).rank)
                .encode()
            : null;
    return new Page(rows, next, more);
  }

  List<Story> featured() {
    return db.query(
        "SELECT "
            + COLUMNS
            + BASE
            + """
            AND a.published_at <= now()
            ORDER BY (a.featured AND a.published_at>=now()-interval '7 days') DESC,
            a.published_at DESC,a.id DESC LIMIT 1
            """,
        Map.of(),
        NewsQueries::story);
  }

  List<Story> securityHighlights() {
    return db.query(
        "SELECT "
            + COLUMNS
            + BASE
            + """
            AND a.published_at BETWEEN now()-interval '14 days' AND now()
            AND EXISTS (SELECT 1 FROM article_categories ac JOIN categories c ON c.id=ac.category_id
              WHERE ac.article_id=a.id AND c.slug='security' AND c.enabled)
            ORDER BY EXISTS (SELECT 1 FROM security_details sd WHERE sd.article_id=a.id
              AND (sd.exploitation_status='Confirmed by CISA' OR lower(sd.severity) IN ('high','critical'))) DESC,
              a.published_at DESC, a.id DESC LIMIT 3
            """,
        Map.of(),
        NewsQueries::story);
  }

  static final String VERIFICATION =
      """
      CASE WHEN sd.exploitation_status='Confirmed by CISA' THEN 'Active exploitation confirmed'
           WHEN sd.verification_status IS NOT NULL THEN sd.verification_status
           WHEN sd.cve_id IS NOT NULL AND sd.verified_at IS NOT NULL THEN 'Vulnerability disclosed'
           ELSE 'Reported but unverified' END
      """;

  private static void appendSecurity(
      StringBuilder where, Map<String, Object> args, SecurityFilter filter) {
    if (filter.severity() != null) {
      if (!Set.of("critical", "high", "medium", "low", "unknown").contains(filter.severity()))
        throw bad("Invalid severity");
      where.append(" AND coalesce(nullif(lower(sd.severity),''),'unknown')=:severity");
      args.put("severity", filter.severity());
    }
    match(where, args, "incidentType", "sd.incident_type", filter.incidentType());
    match(where, args, "patch", "sd.patch_status", filter.patch());
    match(
        where,
        args,
        "affected",
        "concat_ws(' ',sd.affected_company,sd.affected_product)",
        filter.affected());
    if (filter.cve() != null) {
      if (!filter.cve().matches("(?i)CVE-[0-9]{4}-[0-9]{4,19}"))
        throw bad("Invalid CVE identifier");
      where.append(" AND upper(sd.cve_id)=:cve");
      args.put("cve", filter.cve().toUpperCase(Locale.ROOT));
    }
    if (filter.verification() != null) {
      if (!Set.of(
              "Active exploitation confirmed",
              "Vulnerability disclosed",
              "Reported but unverified",
              "Confirmed",
              "Investigation ongoing")
          .contains(filter.verification())) throw bad("Invalid verification status");
      where.append(" AND (" + VERIFICATION + ")=:verification");
      args.put("verification", filter.verification());
    }
  }

  private static void match(
      StringBuilder where,
      Map<String, Object> args,
      String parameter,
      String column,
      String value) {
    if (value == null || value.isBlank()) return;
    if (value.length() > 100) throw bad("Invalid security filter");
    where.append(" AND strpos(lower(" + column + "),lower(:" + parameter + "))>0");
    args.put(parameter, value.strip());
  }

  public Story one(String slug) {
    validSlug(slug);
    return db
        .query(
            "SELECT " + COLUMNS + BASE + " AND a.slug=:slug",
            Map.of("slug", slug),
            NewsQueries::story)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Article unavailable"));
  }

  public List<Story> related(Story story) {
    return db.query(
        "SELECT "
            + COLUMNS
            + BASE
            + """
            AND a.id<>:id AND (EXISTS(SELECT 1 FROM article_topics x JOIN article_topics y USING(topic_id)
              WHERE x.article_id=a.id AND y.article_id=:id) OR EXISTS(SELECT 1 FROM article_categories x
              JOIN article_categories y USING(category_id) WHERE x.article_id=a.id AND y.article_id=:id))
            ORDER BY (SELECT count(*) FROM article_topics x JOIN article_topics y USING(topic_id)
              WHERE x.article_id=a.id AND y.article_id=:id) DESC,
              a.published_at DESC,a.id DESC LIMIT 4
            """,
        Map.of("id", story.id()),
        NewsQueries::story);
  }
}
