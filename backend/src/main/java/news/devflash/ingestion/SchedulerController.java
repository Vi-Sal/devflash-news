package news.devflash.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/internal/ingestion")
public class SchedulerController {
  private final IngestionService service;
  private final String token;

  public SchedulerController(IngestionService service, @Value("${INGESTION_TOKEN:}") String token) {
    this.service = service;
    this.token = token;
  }

  @PostMapping("/run")
  public Map<String, IngestionService.RunReport> run(
      @RequestHeader(value = "Authorization", required = false) String auth) {
    if (token.isBlank())
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Scheduler is not configured");
    if (auth == null
        || auth.length() > 4096
        || !auth.startsWith("Bearer ")
        || !MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8),
            auth.substring(7).getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid scheduler authorization");
    }
    return Map.of("data", service.run(null));
  }
}
