package news.devflash.articles;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyBriefing {
  public record Note(String whyItMatters, String keyTakeaway) {}

  public record Edition(
      LocalDate date,
      List<NewsQueries.Story> articles,
      int minutes,
      Map<String, Integer> categories,
      Map<Long, Note> notes) {}

  private final JdbcTemplate db;
  private final ZoneId zone;

  public DailyBriefing(JdbcTemplate db, @Value("${app.timezone:Asia/Phnom_Penh}") String zone) {
    this.db = db;
    this.zone = ZoneId.of(zone);
  }

  public LocalDate today() {
    return LocalDate.now(zone);
  }

  public List<LocalDate> archive() {
    return db.query(
        """
        SELECT d.digest_date FROM daily_digests d
        WHERE EXISTS (SELECT 1 FROM daily_digest_articles i JOIN articles a ON a.id=i.article_id
          WHERE i.digest_date=d.digest_date AND a.publication_status='PUBLISHED')
        ORDER BY d.digest_date DESC LIMIT 60
        """,
        (r, n) -> r.getObject(1, LocalDate.class));
  }

  int minutes(LocalDate date) {
    return db.queryForObject(
        """
        SELECT coalesce(sum(a.reading_time_minutes),0) FROM daily_digest_articles d
        JOIN articles a ON a.id=d.article_id WHERE d.digest_date=? AND a.publication_status='PUBLISHED'
        """,
        Integer.class,
        date);
  }

  public Edition edition(LocalDate date) {
    if (date.isAfter(today()) || date.isBefore(LocalDate.of(1970, 1, 1)))
      throw NewsQueries.bad("Invalid briefing date");
    var stories =
        db.query(
            "SELECT "
                + NewsQueries.COLUMNS
                + NewsQueries.BASE
                + """
                AND a.id IN (SELECT article_id FROM daily_digest_articles WHERE digest_date=?)
                ORDER BY (SELECT d.position FROM daily_digest_articles d WHERE d.digest_date=? AND d.article_id=a.id)
                """,
            NewsQueries::story,
            date,
            date);
    var categories = new LinkedHashMap<String, Integer>();
    for (var story : stories)
      for (var category : story.categories()) categories.merge(category, 1, Integer::sum);
    var notes = new HashMap<Long, Note>();
    db.query(
        """
        SELECT i.article_id,e.why_it_matters,e.key_takeaway FROM daily_digest_articles i
        JOIN articles a ON a.id=i.article_id AND a.publication_status='PUBLISHED'
        JOIN article_enrichments e ON e.article_id=i.article_id AND e.generation_status='GENERATED'
        WHERE i.digest_date=?
        """,
        r -> {
          notes.put(r.getLong(1), new Note(r.getString(2), r.getString(3)));
        },
        date);
    return new Edition(
        date,
        stories,
        stories.stream().mapToInt(NewsQueries.Story::minutes).sum(),
        categories,
        notes);
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void initialize() {
    lock();
    var today = today();
    var days =
        db.query(
            """
            SELECT DISTINCT (a.published_at AT TIME ZONE ?)::date AS day
            FROM articles a WHERE a.publication_status='PUBLISHED' AND a.published_at>=?
              AND a.published_at<now() AND NOT EXISTS (
                SELECT 1 FROM daily_digests d WHERE d.digest_date=(a.published_at AT TIME ZONE ?)::date)
            ORDER BY day DESC LIMIT 30
            """,
            (r, n) -> r.getObject(1, LocalDate.class),
            zone.getId(),
            boundary(today.minusDays(30)),
            zone.getId());
    for (var day : days) generate(day);
  }

  @Transactional
  public void refresh() {
    lock();
    var today = today();
    generate(today.minusDays(1));
    generate(today);
  }

  private void lock() {
    db.execute("SELECT pg_advisory_xact_lock(814033713)");
  }

  private java.time.OffsetDateTime boundary(LocalDate day) {
    return day.atStartOfDay(zone).toOffsetDateTime();
  }

  void generate(LocalDate day) {
    var candidates =
        db.query(
            "SELECT "
                + NewsQueries.COLUMNS
                + NewsQueries.BASE
                + """
                AND a.published_at>=? AND a.published_at<? AND a.published_at<=now()
                ORDER BY a.featured DESC, EXISTS(SELECT 1 FROM security_details s WHERE s.article_id=a.id
                  AND s.exploitation_status='Confirmed by CISA') DESC, a.published_at DESC, a.id DESC LIMIT 80
                """,
            NewsQueries::story,
            boundary(day),
            boundary(day.plusDays(1)));
    var selected = select(candidates);
    db.update(
        """
        INSERT INTO daily_digests(digest_date) VALUES (?)
        ON CONFLICT(digest_date) DO UPDATE SET generated_at=now()
        """,
        day);
    db.update("DELETE FROM daily_digest_articles WHERE digest_date=?", day);
    var rows = new ArrayList<Object[]>();
    for (int i = 0; i < selected.size(); i++)
      rows.add(new Object[] {day, selected.get(i).id(), i + 1});
    if (!rows.isEmpty())
      db.batchUpdate(
          "INSERT INTO daily_digest_articles(digest_date,article_id,position) VALUES (?,?,?)",
          rows);
  }

  static List<NewsQueries.Story> select(List<NewsQueries.Story> candidates) {
    var result = new ArrayList<NewsQueries.Story>();
    var covered = new HashSet<String>();
    var publishers = new HashSet<String>();
    var remaining = new ArrayList<>(candidates);
    int minutes = 0;
    while (result.size() < 10) {
      NewsQueries.Story next = null;
      int highest = Integer.MIN_VALUE;
      for (int i = 0; i < remaining.size(); i++) {
        var candidate = remaining.get(i);
        if ((!result.isEmpty() && minutes + candidate.minutes() > 15)
            || result.stream().anyMatch(story -> similar(story.title(), candidate.title())))
          continue;
        int score =
            (candidate.featured() ? 18 : 0)
                + (candidate.categories().stream().anyMatch(c -> !covered.contains(c)) ? 14 : 0)
                + (publishers.contains(candidate.source()) ? 0 : 5)
                - i;
        if (score > highest) {
          next = candidate;
          highest = score;
        }
      }
      if (next == null) break;
      result.add(next);
      remaining.remove(next);
      covered.addAll(next.categories());
      publishers.add(next.source());
      minutes += next.minutes();
      if (minutes >= 10 && result.size() >= 5) break;
    }
    return List.copyOf(result);
  }

  private static boolean similar(String a, String b) {
    var left = words(a);
    var right = words(b);
    long common = left.stream().filter(right::contains).count();
    int union = left.size() + right.size() - (int) common;
    return union > 0 && common * 5 >= union * 4;
  }

  private static Set<String> words(String title) {
    return new HashSet<>(List.of(title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")));
  }
}
