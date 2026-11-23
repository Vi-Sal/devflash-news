package news.devflash.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.security.Principal;
import java.util.Map;
import news.devflash.ingestion.IngestionService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
class AdminController {
  private final AdminReads reads;
  private final AdminEdits edits;
  private final AdminAudit audit;
  private final IngestionService ingestion;

  AdminController(
      AdminReads reads, AdminEdits edits, AdminAudit audit, IngestionService ingestion) {
    this.reads = reads;
    this.edits = edits;
    this.audit = audit;
    this.ingestion = ingestion;
  }

  @GetMapping("/auth/csrf")
  Map<String, String> csrf(CsrfToken csrf) {
    return Map.of("token", csrf.getToken(), "headerName", csrf.getHeaderName());
  }

  @GetMapping("/auth/me")
  Object me(Principal user) {
    return data(Map.of("username", user.getName(), "role", "ADMIN"));
  }

  @GetMapping("/dashboard")
  Object dashboard() {
    return data(reads.dashboard());
  }

  @GetMapping("/articles")
  Object articles(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "30") int size,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "") String status) {
    return reads.articles(page, size, q.strip(), status);
  }

  @PatchMapping("/articles/{id}")
  Object article(
      Principal user, @PathVariable long id, @Valid @RequestBody AdminEdits.ArticleInput input) {
    edits.article(user.getName(), id, input);
    return data(id);
  }

  @DeleteMapping("/articles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void duplicate(Principal user, @PathVariable long id) {
    edits.removeDuplicate(user.getName(), id);
  }

  @PostMapping("/articles/{id}/retry")
  Object retryArticle(Principal user, @PathVariable long id) {
    reads.requireProcessable(id);
    audit.record(user.getName(), "retry_processing", "article", id);
    return data(ingestion.retryArticle(id));
  }

  @GetMapping("/sources")
  Object sources() {
    return data(reads.sources());
  }

  @PostMapping("/sources")
  @ResponseStatus(HttpStatus.CREATED)
  Object addSource(Principal user, @Valid @RequestBody AdminEdits.SourceInput input) {
    return data(edits.source(user.getName(), null, input));
  }

  @PatchMapping("/sources/{id}")
  Object source(
      Principal user, @PathVariable long id, @Valid @RequestBody AdminEdits.SourceInput input) {
    return data(edits.source(user.getName(), id, input));
  }

  @PostMapping("/sources/{id}/test")
  Object test(Principal user, @PathVariable long id) {
    audit.record(user.getName(), "test", "source", id);
    return data(ingestion.test(id));
  }

  @GetMapping("/categories")
  Object categories() {
    return data(reads.categories());
  }

  @PostMapping("/categories")
  @ResponseStatus(HttpStatus.CREATED)
  Object addCategory(Principal user, @Valid @RequestBody AdminEdits.TaxonomyInput input) {
    return data(edits.taxonomy(user.getName(), null, input, true));
  }

  @PatchMapping("/categories/{id}")
  Object category(
      Principal user, @PathVariable long id, @Valid @RequestBody AdminEdits.TaxonomyInput input) {
    return data(edits.taxonomy(user.getName(), id, input, true));
  }

  @GetMapping("/topics")
  Object topics() {
    return data(reads.topics());
  }

  @PostMapping("/topics")
  @ResponseStatus(HttpStatus.CREATED)
  Object addTopic(Principal user, @Valid @RequestBody AdminEdits.TaxonomyInput input) {
    return data(edits.taxonomy(user.getName(), null, input, false));
  }

  @PatchMapping("/topics/{id}")
  Object topic(
      Principal user, @PathVariable long id, @Valid @RequestBody AdminEdits.TaxonomyInput input) {
    return data(edits.taxonomy(user.getName(), id, input, false));
  }

  record MergeInput(@NotNull @Positive Long destinationId) {}

  @PostMapping("/topics/{id}/merge")
  Object merge(Principal user, @PathVariable long id, @Valid @RequestBody MergeInput input) {
    edits.mergeTopics(user.getName(), id, input.destinationId());
    return data(input.destinationId());
  }

  @GetMapping("/ingestion/runs")
  Object runs(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
    return reads.runs(page, size);
  }

  record RunInput(@Positive Long sourceId) {}

  @PostMapping("/ingestion/run")
  Object run(Principal user, @Valid @RequestBody(required = false) RunInput input) {
    Long sourceId = input == null ? null : input.sourceId();
    audit.record(user.getName(), "run_ingestion", "source", sourceId);
    return data(ingestion.run(sourceId));
  }

  @GetMapping("/settings")
  Object settings() {
    return data(reads.settings());
  }

  @PatchMapping("/settings")
  Object settings(Principal user, @RequestBody Map<String, String> values) {
    edits.settings(user.getName(), values);
    return data(reads.settings());
  }

  @GetMapping("/audit-logs")
  Object auditLogs(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
    return reads.audit(page, size);
  }

  @ExceptionHandler(AdminEdits.Missing.class)
  ResponseEntity<?> missing() {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Record not found");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<?> conflict() {
    return error(
        HttpStatus.CONFLICT, "CONFLICT", "Check for a duplicate or an unavailable linked record");
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<?> invalid() {
    return error(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Check the submitted values and permitted feed hosts");
  }

  private static Map<String, Object> data(Object value) {
    return Map.of("data", value);
  }

  private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("error", Map.of("code", code, "message", message)));
  }
}
