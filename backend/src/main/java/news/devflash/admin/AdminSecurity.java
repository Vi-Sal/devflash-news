package news.devflash.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class AdminSecurity {
  private static final String AUTH = "/api/v1/admin/auth/";

  @Bean
  PasswordEncoder adminPasswordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService adminUserDetails(JdbcTemplate db) {
    return username ->
        db
            .query(
                "select username,password_hash,enabled from admin_users where username=?",
                (rs, i) ->
                    (UserDetails)
                        User.withUsername(rs.getString("username"))
                            .password(rs.getString("password_hash"))
                            .roles("ADMIN")
                            .disabled(!rs.getBoolean("enabled"))
                            .build(),
                username)
            .stream()
            .findFirst()
            .orElseThrow(() -> new UsernameNotFoundException("Unknown administrator"));
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, AdminAudit audit) throws Exception {
    var attempts = new LoginThrottle(Clock.systemUTC());
    http.authorizeHttpRequests(
            rules ->
                rules
                    .requestMatchers(AUTH + "csrf", AUTH + "login", "/admin/login")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**", "/admin", "/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .anyRequest()
                    .permitAll())
        .csrf(
            csrf ->
                csrf.ignoringRequestMatchers(
                    PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/api/v1/internal/ingestion/run"),
                    PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.POST, "/api/v1/articles/*/events")))
        .requestCache(cache -> cache.disable())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (req, res, error) -> {
                          if (req.getRequestURI().startsWith(req.getContextPath() + "/admin"))
                            res.sendRedirect(req.getContextPath() + "/admin/login");
                          else reject(res, 401, "AUTH_REQUIRED", "Sign in to continue");
                        })
                    .accessDeniedHandler(
                        (req, res, error) ->
                            reject(res, 403, "FORBIDDEN", "Access denied. Refresh and try again")))
        .formLogin(
            form ->
                form.loginProcessingUrl(AUTH + "login")
                    .successHandler(
                        (req, res, authentication) -> {
                          attempts.clear(req.getRemoteAddr());
                          audit.signIn(authentication.getName());
                          res.setStatus(204);
                          res.setHeader("Cache-Control", "no-store");
                        })
                    .failureHandler(
                        (req, res, error) ->
                            reject(
                                res, 401, "INVALID_CREDENTIALS", "Incorrect username or password")))
        .logout(
            logout ->
                logout
                    .logoutUrl(AUTH + "logout")
                    .invalidateHttpSession(true)
                    .addLogoutHandler(
                        (req, res, authentication) -> {
                          if (authentication != null)
                            audit.record(authentication.getName(), "logout", "session", null);
                        })
                    .deleteCookies("JSESSIONID")
                    .logoutSuccessHandler((req, res, authentication) -> res.setStatus(204)))
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(
                        policy ->
                            policy.policyDirectives(
                                "default-src 'self'; script-src 'self'; style-src 'self'"
                                    + " 'unsafe-inline'; img-src 'self' data: https:; font-src"
                                    + " 'self'; connect-src 'self'; frame-ancestors 'none';"
                                    + " base-uri 'self'; object-src 'none'; form-action 'self'"))
                    .frameOptions(frame -> frame.deny()))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                  throws ServletException, IOException {
                if ("POST".equals(req.getMethod())
                    && (req.getContextPath() + AUTH + "login").equals(req.getRequestURI())) {
                  if (!attempts.allow(req.getRemoteAddr())) {
                    res.setHeader("Retry-After", "900");
                    reject(res, 429, "RATE_LIMITED", "Too many attempts. Try again in 15 minutes");
                    return;
                  }
                  if (req.getParameter("username") == null
                      || req.getParameter("username").length() > 80
                      || req.getParameter("password") == null
                      || req.getParameter("password").getBytes(StandardCharsets.UTF_8).length
                          > 72) {
                    reject(res, 401, "INVALID_CREDENTIALS", "Incorrect username or password");
                    return;
                  }
                }
                chain.doFilter(req, res);
              }
            },
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  private static void reject(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response
        .getWriter()
        .write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
  }

  static final class LoginThrottle {
    private record Attempts(long start, int count) {}

    private final Clock clock;
    private final Map<String, Attempts> addresses = new HashMap<>();
    private static final long WINDOW = Duration.ofMinutes(15).toMillis();

    LoginThrottle(Clock clock) {
      this.clock = clock;
    }

    synchronized boolean allow(String address) {
      long now = clock.millis();
      Attempts recent = addresses.get(address);
      if (recent == null && addresses.size() >= 2048) {
        addresses.values().removeIf(entry -> now - entry.start() >= WINDOW);
        if (addresses.size() >= 2048) return false;
      }
      if (recent == null || now - recent.start() >= WINDOW) recent = new Attempts(now, 0);
      if (recent.count() >= 8) return false;
      addresses.put(address, new Attempts(recent.start(), recent.count() + 1));
      return true;
    }

    synchronized void clear(String address) {
      addresses.remove(address);
    }
  }
}
