package news.devflash.articles;

import jakarta.servlet.http.HttpSession;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
public class NewsApi {
  private final NewsQueries news;
  private final JdbcTemplate db;
  private final ZoneId zone;
  private final String youtube;
  private final DailyBriefing briefing;

  public NewsApi(
      NewsQueries news,
      JdbcTemplate db,
      DailyBriefing briefing,
      @Value("${app.timezone:Asia/Phnom_Penh}") String zone,
      @Value("${YOUTUBE_CHANNEL_URL:}") String youtube) {
    this.news = news;
    this.briefing = briefing;
    this.db = db;
    this.zone = ZoneId.of(zone);
    this.youtube = youtube;
  }

  String setting(String key, String fallback) {
    return db
        .query(
            "SELECT setting_value FROM site_settings WHERE setting_key=?",
            (r, n) -> r.getString(1),
            key)
        .stream()
        .findFirst()
        .orElse(fallback);
  }

  @GetMapping({"/articles", "/search"})
  NewsQueries.Page feed(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String topic,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "newest") String sort,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(name = "feed", required = false) String mode,
      @RequestParam(required = false) String following,
      @RequestParam(required = false) Long exclude) {
    if (following != null && following.length() > 1500)
      throw NewsQueries.bad("Invalid followed topics");
    return news.feed(
        category,
        topic,
        q,
        from,
        to,
        sort,
        limit == null ? Integer.parseInt(setting("defaultPageSize", "10")) : limit,
        cursor,
        zone,
        mode,
        following == null || following.isBlank()
            ? List.of()
            : Arrays.asList(following.split(",", -1)),
        exclude,
        null);
  }

  NewsQueries.Page feed(
      String category,
      String topic,
      String q,
      LocalDate from,
      LocalDate to,
      String sort,
      Integer limit,
      String cursor) {
    return feed(category, topic, q, from, to, sort, limit, cursor, null, null, null);
  }

  @GetMapping("/featured")
  Map<String, ?> featured() {
    return Map.of("data", news.featured());
  }

  @GetMapping("/security/highlights")
  Map<String, ?> securityHighlights() {
    return Map.of("data", news.securityHighlights());
  }

  @GetMapping("/security")
  Map<String, ?> security(
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String incidentType,
      @RequestParam(required = false) String affected,
      @RequestParam(required = false) String cve,
      @RequestParam(required = false) String patch,
      @RequestParam(required = false) String verification,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(defaultValue = "10") int limit,
      @RequestParam(required = false) String cursor) {
    var page =
        news.feed(
            "security",
            null,
            "",
            from,
            to,
            "newest",
            limit,
            cursor,
            zone,
            null,
            List.of(),
            null,
            new NewsQueries.SecurityFilter(
                severity, incidentType, affected, cve, patch, verification));
    var details = new HashMap<String, Map<String, Object>>();
    if (!page.data().isEmpty()) {
      var ids = page.data().stream().map(NewsQueries.Story::id).toList();
      String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
      db.queryForList(
              "SELECT a.slug,sd.affected_company,sd.affected_product,sd.incident_type,"
                  + " sd.severity,sd.cve_id,sd.cvss_score,sd.exploitation_status,sd.patch_status,sd.mitigation,"
                  + NewsQueries.VERIFICATION
                  + " AS verification FROM articles a LEFT JOIN security_details sd"
                  + " ON sd.article_id=a.id WHERE a.id IN ("
                  + placeholders
                  + ")",
              ids.toArray())
          .forEach(row -> details.put((String) row.remove("slug"), row));
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("data", page.data());
    result.put("nextCursor", page.nextCursor());
    result.put("hasMore", page.hasMore());
    result.put("security", details);
    return result;
  }

  @GetMapping("/articles/{slug}")
  Map<String, Object> article(@PathVariable String slug) {
    var a = news.one(slug);
    var enrichment =
        db.queryForList(
            "SELECT"
                + " what_happened,why_it_matters,key_takeaway,developer_impact,provider_name,model_name"
                + " FROM article_enrichments WHERE article_id=? AND generation_status='GENERATED'"
                + " AND what_happened IS NOT NULL",
            a.id());
    var security =
        db.queryForList(
            "SELECT affected_company,affected_product,incident_type,severity,cve_id,cvss_score,"
                + " affected_versions,exploitation_status,patch_status,mitigation,verified_at,"
                + NewsQueries.VERIFICATION
                + " AS verification FROM security_details sd WHERE article_id=?",
            a.id());
    var detail = new HashMap<String, Object>();
    detail.put("story", a);
    detail.put("enrichment", enrichment.stream().findFirst().orElse(null));
    detail.put("security", security.stream().findFirst().orElse(null));
    return Map.of("data", detail);
  }

  @GetMapping("/articles/{slug}/related")
  Map<String, ?> related(@PathVariable String slug) {
    return Map.of("data", news.related(news.one(slug)));
  }

  public record Event(String type) {}

  @PostMapping("/articles/{slug}/events")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void event(@PathVariable String slug, @RequestBody Event event, HttpSession session) {
    if (!Set.of("view", "share").contains(Objects.toString(event.type(), "")))
      throw NewsQueries.bad("Invalid event");
    long id = news.one(slug).id();
    String key = event.type() + id;
    synchronized (session) {
      @SuppressWarnings("unchecked")
      var seen = (Set<String>) session.getAttribute("events");
      if (seen == null) {
        seen = new HashSet<>();
        session.setAttribute("events", seen);
      }
      if (seen.size() >= 500 || seen.contains(key)) return;
      db.update(
          """
          INSERT INTO article_metrics(article_id,view_count,share_count) VALUES(?,?,?)
          ON CONFLICT(article_id) DO UPDATE SET view_count=article_metrics.view_count+excluded.view_count,
          share_count=article_metrics.share_count+excluded.share_count,updated_at=now()
          """,
          id,
          "view".equals(event.type()) ? 1 : 0,
          "share".equals(event.type()) ? 1 : 0);
      seen.add(key);
    }
  }

  @GetMapping("/categories")
  Map<String, ?> categories() {
    return Map.of(
        "data",
        db.queryForList(
            "SELECT name,slug,description,icon,accent_color FROM categories WHERE enabled ORDER BY"
                + " id"));
  }

  @GetMapping("/topics")
  Map<String, ?> topics() {
    return Map.of(
        "data",
        db.queryForList(
            "SELECT t.name,t.slug,t.description,count(a.id) AS stories FROM topics t               "
                + "  LEFT JOIN article_topics x ON x.topic_id=t.id LEFT JOIN articles a ON"
                + " a.id=x.article_id                 AND a.publication_status='PUBLISHED' GROUP BY"
                + " t.id ORDER BY t.name LIMIT 250"));
  }

  @GetMapping({"/categories/{slug}", "/topics/{slug}"})
  Map<String, ?> taxonomy(@PathVariable String slug, jakarta.servlet.http.HttpServletRequest req) {
    NewsQueries.validSlug(slug);
    String sql =
        req.getRequestURI().contains("/categories/")
            ? "SELECT name,slug,description FROM categories WHERE enabled AND slug=?"
            : "SELECT t.name,t.slug,t.description,(SELECT count(*) FROM article_topics x JOIN"
                + " articles a ON a.id=x.article_id AND a.publication_status='PUBLISHED' WHERE"
                + " x.topic_id=t.id) AS stories FROM topics t WHERE t.slug=?";
    var rows = db.queryForList(sql, slug);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Section unavailable");
    return Map.of("data", rows.getFirst());
  }

  @GetMapping("/sources")
  Map<String, ?> sources() {
    return Map.of(
        "data",
        db.queryForList("SELECT name,website_url FROM sources WHERE enabled ORDER BY name"));
  }

  @GetMapping("/trending")
  Map<String, ?> trending() {
    return Map.of(
        "data",
        db.queryForList(
            """
            SELECT t.name,t.slug,count(*) AS stories,count(DISTINCT a.source_id) AS publishers,
                   round(sum((1+case when a.featured then 1 else 0 end+ln(1+least(coalesce(m.view_count,0)+2*coalesce(m.share_count,0),1000))) /
                     (1+greatest(0,extract(epoch FROM now()-a.published_at))/86400))::numeric,3)
                       +count(DISTINCT a.source_id) AS score
            FROM topics t JOIN article_topics atp ON atp.topic_id=t.id JOIN articles a ON a.id=atp.article_id
            LEFT JOIN article_metrics m ON m.article_id=a.id WHERE a.publication_status='PUBLISHED'
            AND a.published_at BETWEEN now()-(? * interval '1 day') AND now()
            GROUP BY t.id ORDER BY score DESC,t.name LIMIT 10
            """,
            Integer.parseInt(setting("trendingWindowDays", "7"))));
  }

  private Object[] bounds(LocalDate day) {
    return new Object[] {
      day.atStartOfDay(zone).toOffsetDateTime(),
      day.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
    };
  }

  @GetMapping("/stats/today")
  Map<String, ?> stats() {
    LocalDate day = LocalDate.now(zone);
    Object[] range = bounds(day);
    var counts =
        db.queryForList(
            """
            SELECT c.slug,count(a.id) AS count,
              count(a.id) FILTER (WHERE a.original_title ~* '(^|[^a-z])(launch(es|ed)?|releas(e|es|ed)|introduc(e|es|ed|ing)|announc(e|es|ed|ing)|rolls out|general availability)([^a-z]|$)') AS releases
            FROM categories c LEFT JOIN article_categories ac ON ac.category_id=c.id
            LEFT JOIN articles a ON a.id=ac.article_id AND a.publication_status='PUBLISHED' AND a.published_at>=? AND a.published_at<?
            WHERE c.enabled GROUP BY c.id ORDER BY c.id
            """,
            range);
    return Map.of(
        "data",
        Map.of(
            "date",
            day,
            "categories",
            counts,
            "total",
            db.queryForObject(
                "SELECT count(*) FROM articles WHERE publication_status='PUBLISHED' AND"
                    + " published_at>=? AND published_at<?",
                Long.class,
                range),
            "minutes",
            briefing.minutes(day)));
  }

  @GetMapping("/daily")
  Map<String, ?> daily() {
    return Map.of("data", briefing.edition(briefing.today()));
  }

  @GetMapping("/daily/archive")
  Map<String, ?> dailyArchive() {
    return Map.of("data", briefing.archive());
  }

  @GetMapping("/daily/{date}")
  Map<String, ?> dailyOn(@PathVariable LocalDate date) {
    return Map.of("data", briefing.edition(date));
  }

  @GetMapping("/site")
  Map<String, ?> site() {
    return Map.of(
        "data",
        Map.of(
            "siteTitle",
            setting("siteTitle", "DevFlash"),
            "tagline",
            setting("tagline", "Tech news in a flash."),
            "youtubeUrl",
            setting("youtubeUrl", youtube),
            "timezone",
            zone.getId()));
  }

  @GetMapping("/health")
  Map<String, ?> health() {
    db.queryForObject("SELECT 1", Integer.class);
    return Map.of("data", Map.of("status", "UP"));
  }
}
