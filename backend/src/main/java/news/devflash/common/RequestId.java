package news.devflash.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class RequestId extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String id = UUID.randomUUID().toString();
    response.setHeader("X-Request-Id", id);
    try (var ignored = MDC.putCloseable("requestId", id)) {
      chain.doFilter(request, response);
    }
  }
}
