package news.devflash.summarization;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnrichmentService {
  private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ArticleSummarizationService provider;

  private final ZoneId zone;

  @Value("${AI_INPUT_COST_PER_MILLION_USD:}")
  private String inputRate = "";

  @Value("${AI_OUTPUT_COST_PER_MILLION_USD:}")
  private String outputRate = "";

  public EnrichmentService(
      JdbcTemplate db,
      TransactionTemplate tx,
      ArticleSummarizationService provider,
      @Value("${app.timezone:Asia/Phnom_Penh}") String zone) {
    this.zone = ZoneId.of(zone);
    this.db = db;
    this.tx = tx;
    this.provider = provider;
  }

  public String regenerate(long articleId) {
    return process(articleId, true);
  }

  private String process(long articleId, boolean manual) {
    List<ArticleSummarizationService.Evidence> rows =
        db.query(
            """
            select a.original_title,s.name,a.source_description from articles a join sources s on s.id=a.source_id
            where a.id=?
            """,
            (r, i) ->
                new ArticleSummarizationService.Evidence(
                    r.getString(1), r.getString(2), r.getString(3)),
            articleId);
    if (rows.isEmpty())
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Article not found");
    var evidence = rows.getFirst();
    if (!provider.available() || evidence.excerpt() == null || evidence.excerpt().length() < 100)
      return "PREVIEW";
    String enabled = setting("aiEnabled", "false");
    int limit;
    try {
      limit = Math.min(1000, Integer.parseInt(setting("aiDailyBudget", "0")));
    } catch (NumberFormatException invalid) {
      return "PREVIEW";
    }
    if (!Boolean.parseBoolean(enabled) || limit < 1) return "PREVIEW";

    // Reserve budget and one article attempt atomically, then release the connection during the
    // provider call.
    LocalDate usageDate = LocalDate.now(zone);
    Integer attempt =
        tx.execute(
            status -> {
              var attempts =
                  db.queryForList(
                      """
                      insert into article_enrichments(article_id,provider_name,model_name,generation_status,attempts,generated_at)
                      values (?,?,?,'PROCESSING',1,now()) on conflict(article_id) do update set
                      generation_status='PROCESSING',attempts=article_enrichments.attempts+1,
                      provider_name=excluded.provider_name,model_name=excluded.model_name,generated_at=now()
                      where article_enrichments.attempts<2 and (article_enrichments.generation_status='FAILED'
                      or (? and article_enrichments.generation_status in ('GENERATED','INSUFFICIENT_EVIDENCE'))
                      or (article_enrichments.generation_status='PROCESSING' and article_enrichments.generated_at<now()-interval '5 minutes'))
                      returning attempts
                      """,
                      Integer.class,
                      articleId,
                      provider.provider(),
                      provider.model(),
                      manual);
              if (attempts.isEmpty()) return 0;
              if (db.update(
                      """
                      insert into ai_usage(usage_date,calls) values (?,1)
                      on conflict(usage_date) do update set calls=ai_usage.calls+1 where ai_usage.calls<?
                      """,
                      usageDate,
                      limit)
                  == 0) {
                status.setRollbackOnly();
                return -1;
              }
              return attempts.getFirst();
            });
    if (attempt == null || attempt == -1) return "DAILY_LIMIT";
    if (attempt == 0)
      return db.queryForObject(
          "select generation_status from article_enrichments where article_id=?",
          String.class,
          articleId);

    try {
      var result = provider.summarize(evidence);
      trackUsage(usageDate, result);
      var answer = result.explanation();
      if (answer.isEmpty()) {
        finish(articleId, attempt, "INSUFFICIENT_EVIDENCE");
        return "INSUFFICIENT_EVIDENCE";
      }
      var e = answer.get();
      db.update(
          """
          update article_enrichments set what_happened=?,why_it_matters=?,key_takeaway=?,developer_impact=?,
          generation_status='GENERATED',generated_at=now() where article_id=? and attempts=?
          """,
          e.whatHappened(),
          e.whyItMatters(),
          e.keyTakeaway(),
          e.developerImpact(),
          articleId,
          attempt);
      return "GENERATED";
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      finish(articleId, attempt, "FAILED");
      log.warn("event=ai_enrichment_failed article={} attempt={}", articleId, attempt);
      return "FAILED";
    }
  }

  private void trackUsage(LocalDate date, ArticleSummarizationService.Result result) {
    if (result.inputTokens() == 0 && result.outputTokens() == 0) return;
    BigDecimal cost = null;
    try {
      var input = new BigDecimal(inputRate);
      var output = new BigDecimal(outputRate);
      if (input.signum() >= 0 && output.signum() >= 0)
        cost =
            input
                .multiply(BigDecimal.valueOf(result.inputTokens()))
                .add(output.multiply(BigDecimal.valueOf(result.outputTokens())))
                .movePointLeft(6);
    } catch (NumberFormatException ratesUnavailable) {
      // Requests and tokens remain measurable without a configured model price.
    }
    db.update(
        """
        UPDATE ai_usage SET input_tokens=input_tokens+?,output_tokens=output_tokens+?,
          estimated_cost_usd=CASE WHEN CAST(? AS numeric) IS NULL THEN estimated_cost_usd
            ELSE coalesce(estimated_cost_usd,0)+? END WHERE usage_date=?
        """,
        result.inputTokens(),
        result.outputTokens(),
        cost,
        cost,
        date);
  }

  public void retryPending(int max) {
    if (!provider.available() || !Boolean.parseBoolean(setting("aiEnabled", "false"))) return;
    List<Long> ids =
        db.queryForList(
            """
            select a.id from articles a left join article_enrichments e on e.article_id=a.id
            where a.publication_status='PUBLISHED' and length(a.source_description)>=100 and
            (e.id is null or (e.attempts<2 and e.generation_status in ('FAILED','PROCESSING') and
            e.generated_at < now()-interval '5 minutes')) order by a.published_at desc,a.id desc limit ?
            """,
            Long.class,
            max);
    for (long id : ids) if ("DAILY_LIMIT".equals(process(id, false))) break;
  }

  private void finish(long id, int attempt, String result) {
    db.update(
        "update article_enrichments set generation_status=?,generated_at=now() where article_id=?"
            + " and attempts=?",
        result,
        id,
        attempt);
  }

  private String setting(String key, String fallback) {
    return db
        .queryForList(
            "select setting_value from site_settings where setting_key=?", String.class, key)
        .stream()
        .findFirst()
        .orElse(fallback);
  }
}
