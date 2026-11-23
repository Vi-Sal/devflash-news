package news.devflash.admin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AdminReads {
  private final JdbcTemplate db;
  private final ZoneId zone;
  private final String youtubeUrl;

  AdminReads(
      JdbcTemplate db,
      @Value("${app.timezone:Asia/Phnom_Penh}") String zone,
      @Value("${YOUTUBE_CHANNEL_URL:}") String youtubeUrl) {
    this.db = db;
    this.zone = ZoneId.of(zone);
    this.youtubeUrl = youtubeUrl;
  }

  Map<String, Object> dashboard() {
    String todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toString();
    var response =
        new LinkedHashMap<>(
            db.queryForMap(
                """
                select (select count(*) from articles) as articles,
                       (select count(*) from articles where ingested_at >= cast(? as timestamptz)) as collected_today,
                       (select count(*) from sources where enabled) as active_sources,
                       (select count(*) from sources where enabled and last_error is not null) as failed_sources,
                       (select count(*) from articles where upper(processing_status)='PENDING') as awaiting_processing,
                       (select coalesce(sum(calls),0) from ai_usage where usage_date=cast(? as date)) as ai_calls_today,
                       (select max(last_success_at) from sources) as last_success,
                       (select coalesce(sum(view_count),0) from article_metrics) as views,
                       (select coalesce(sum(share_count),0) from article_metrics) as shares
                """,
                todayStart,
                LocalDate.now(zone)));
    response.put(
        "distribution",
        db.queryForList(
            """
            select c.id,c.name,count(ac.article_id) as articles from categories c
            left join article_categories ac on ac.category_id=c.id group by c.id,c.name order by articles desc,c.name
            """));
    response.put("recent_runs", runs(0, 5).data());
    return response;
  }

  void requireProcessable(long id) {
    if (!Boolean.TRUE.equals(
        db.queryForObject(
            "select exists(select 1 from articles where id=? and processing_status<>'DUPLICATE')",
            Boolean.class,
            id))) throw new AdminEdits.Missing();
  }

  record Page(List<Map<String, Object>> data, boolean hasMore) {}

  Page articles(int page, int size, String q, String status) {
    if (q.length() > 180 || !List.of("", "PUBLISHED", "DRAFT").contains(status))
      throw invalid("Invalid article filter");
    String sql =
        """
        select a.id,a.title,a.slug,a.summary,a.original_url,a.source_description,a.published_at,a.original_title,
               a.processing_status,a.publication_status,a.featured,s.name as source_name,
               coalesce((select string_agg(ac.category_id::text,',' order by ac.category_id)
                         from article_categories ac where ac.article_id=a.id),'') as category_ids,
               coalesce((select string_agg(atp.topic_id::text,',' order by atp.topic_id)
                         from article_topics atp where atp.article_id=a.id),'') as topic_ids
        from articles a join sources s on s.id=a.source_id
        where a.processing_status<>'DUPLICATE' and (?='' or a.title ilike '%' || ? || '%') and (?='' or a.publication_status=?)
        order by a.ingested_at desc,a.id desc
        """;
    return page(sql, List.of(q, q, status, status), page, size);
  }

  Page runs(int page, int size) {
    return page(
        """
        select r.id,r.source_id,s.name as source_name,r.started_at,r.finished_at,r.status,
               greatest(0,extract(epoch from (coalesce(r.finished_at,now())-r.started_at)))::int as duration_seconds,
               r.fetched_count,r.inserted_count,r.duplicate_count,r.error_count,r.error_message
        from ingestion_runs r left join sources s on s.id=r.source_id order by r.started_at desc,r.id desc
        """,
        List.of(),
        page,
        size);
  }

  Page audit(int page, int size) {
    return page(
        """
        select l.id,l.created_at,u.username,l.action,l.entity_type,l.entity_id
        from admin_audit_logs l left join admin_users u on u.id=l.admin_user_id
        order by l.created_at desc,l.id desc
        """,
        List.of(),
        page,
        size);
  }

  private Page page(String sql, List<Object> params, int page, int size) {
    if (page < 0 || page > 100000 || size < 1 || size > 100) throw invalid("Invalid page");
    var args = new ArrayList<>(params);
    args.add(size + 1);
    args.add((long) page * size);
    var rows = db.queryForList(sql + " limit ? offset ?", args.toArray());
    return new Page(rows.stream().limit(size).toList(), rows.size() > size);
  }

  List<Map<String, Object>> sources() {
    return db.queryForList(
        """
        select id,name,website_url,feed_url,source_type,enabled,last_fetched_at,last_success_at,last_error
        from sources order by name,id
        """);
  }

  List<Map<String, Object>> categories() {
    return db.queryForList(
        """
        select c.id,c.name,c.slug,c.description,c.icon,c.accent_color,c.enabled,count(a.article_id) as articles
        from categories c left join article_categories a on a.category_id=c.id group by c.id order by c.name
        """);
  }

  List<Map<String, Object>> topics() {
    return db.queryForList(
        """
        select t.id,t.name,t.slug,t.description,count(a.article_id) as articles
        from topics t left join article_topics a on a.topic_id=t.id group by t.id order by t.name
        """);
  }

  Map<String, String> settings() {
    var settings =
        new LinkedHashMap<>(
            Map.of(
                "siteTitle",
                "DevFlash",
                "tagline",
                "Tech news in a flash.",
                "youtubeUrl",
                youtubeUrl,
                "ingestionBatchLimit",
                "30",
                "defaultPageSize",
                "10",
                "aiEnabled",
                "false",
                "aiDailyBudget",
                "0",
                "trendingWindowDays",
                "7"));
    db.query(
        "select setting_key,setting_value from site_settings",
        rs -> {
          if (settings.containsKey(rs.getString(1))) settings.put(rs.getString(1), rs.getString(2));
        });
    return settings;
  }

  static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
