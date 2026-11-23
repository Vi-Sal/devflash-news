package news.devflash.common;

import java.util.Map;
import org.slf4j.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<?> status(ResponseStatusException e) {
    return error(e.getStatusCode(), e.getReason() == null ? "Request failed" : e.getReason());
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.bind.MethodArgumentNotValidException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class
  })
  ResponseEntity<?> invalid(Exception e) {
    return error(HttpStatus.BAD_REQUEST, "Invalid request parameters");
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> unavailable(DataAccessException e) {
    log.error("Database request failed: {}", e.getClass().getSimpleName());
    return error(HttpStatus.SERVICE_UNAVAILABLE, "Newsroom database unavailable");
  }

  private ResponseEntity<?> error(HttpStatusCode status, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "error",
                Map.of(
                    "code",
                    status.value() == 400 ? "VALIDATION_ERROR" : "HTTP_" + status.value(),
                    "message",
                    message)));
  }
}
