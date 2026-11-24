package news.devflash.ingestion;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeedClient {
  public static final String KEV_URL =
      "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json";
  static final String FED_NEWS_URL = "https://www.federalreserve.gov/feeds/press_other.xml";
  private static final Set<String> BUILTIN_FEEDS = Set.of(KEV_URL, FED_NEWS_URL);
  private static final Set<String> BUILTIN_HOSTS = Set.of("www.cisa.gov", "www.federalreserve.gov");
  static final int BODY_LIMIT = 3 * 1024 * 1024;
  private final Set<String> hosts;
  private final CloseableHttpClient http;
  private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();

  public FeedClient(@Value("${FEED_ALLOWED_HOSTS:go.dev,kubernetes.io}") String allowedHosts) {
    hosts =
        Arrays.stream(allowedHosts.split(","))
            .map(String::trim)
            .map(s -> s.toLowerCase(Locale.ROOT))
            .filter(s -> s.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)+"))
            .collect(Collectors.toUnmodifiableSet());
    // Supply the validated addresses to the socket connector itself. TLS still verifies the DNS
    // hostname.
    var resolver =
        new DnsResolver() {
          public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!hosts.contains(host.toLowerCase(Locale.ROOT))
                && !BUILTIN_HOSTS.contains(host.toLowerCase(Locale.ROOT)))
              throw new UnknownHostException("Feed host is disabled");
            return checkedAddresses(InetAddress.getAllByName(host));
          }

          public String resolveCanonicalHostname(String host) {
            return host;
          }
        };
    var manager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setMaxConnTotal(3)
            .setMaxConnPerRoute(2)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(4))
                    .setSocketTimeout(Timeout.ofSeconds(7))
                    .build())
            .build();
    http =
        HttpClients.custom()
            .setConnectionManager(manager)
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .setUserAgent("DevFlash/1.0 (RSS and Atom reader)")
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofSeconds(7))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(3))
                    .build())
            .build();
  }

  public URI validate(String value) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("Invalid feed URL");
    }
    if (value.length() > 2048
        || !"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || !(hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
            || BUILTIN_FEEDS.contains(value))
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || !uri.getHost().matches("(?i)[a-z0-9.-]+\\.[a-z]{2,63}")) {
      throw new IllegalArgumentException("Use HTTPS on an approved publisher host");
    }
    return uri;
  }

  static InetAddress[] checkedAddresses(InetAddress[] addresses) throws UnknownHostException {
    if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !isPublic(a))) {
      throw new UnknownHostException("Feed host resolves to a reserved address");
    }
    return addresses;
  }

  static boolean isPublic(InetAddress address) {
    byte[] bytes = address.getAddress();
    int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) return false;
    if (bytes.length == 16) {
      // Global unicast only. Exclude transition, protocol assignment and documentation prefixes.
      return (a & 0xe0) == 0x20
          && !(a == 0x20
              && (b == 0x02 || b == 0x01 && (c < 2 || c == 0x0d && (bytes[3] & 255) == 0xb8)))
          && !(a == 0x3f && b == 0xff);
    }
    return a != 0
        && a < 224
        && !(a == 100 && b >= 64 && b < 128)
        && !(a == 192 && (b == 0 || b == 88 && c == 99))
        && !(a == 198 && (b == 18 || b == 19 || b == 51 && c == 100))
        && !(a == 203 && b == 0 && c == 113);
  }

  public record Payload(
      int status, byte[] body, String etag, String modified, String redirect, String retryAfter) {}

  static class FetchFailure extends IOException {
    final Instant retryAt;

    FetchFailure(String message, Instant retryAt) {
      super(message);
      this.retryAt = retryAt;
    }
  }

  public Payload fetch(String value, String etag, String modified) throws IOException {
    return fetch(value, etag, modified, false);
  }

  Payload fetchPage(String url) throws IOException {
    return fetch(url, null, null, true);
  }

  private Payload fetch(String value, String etag, String modified, boolean articlePage)
      throws IOException {
    URI origin = validate(value);
    URI uri = origin;
    int redirects = 0, retries = 0;
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(22);
    while (System.nanoTime() < deadline) {
      HttpGet request = new HttpGet(uri);
      request.setHeader(
          "Accept",
          articlePage
              ? "text/html"
              : "application/atom+xml, application/rss+xml, application/feed+json, application/xml,"
                  + " application/json");
      if (origin.equals(uri)) {
        conditional(request, "If-None-Match", etag);
        conditional(request, "If-Modified-Since", modified);
      }
      var timer =
          deadlines.schedule(
              () -> {
                request.cancel();
              },
              Math.max(1L, deadline - System.nanoTime()),
              TimeUnit.NANOSECONDS);
      Payload response;
      try {
        response =
            http.execute(
                request,
                r -> {
                  byte[] body = new byte[0];
                  if (r.getCode() == 200 && r.getEntity() != null) {
                    body = r.getEntity().getContent().readNBytes(BODY_LIMIT + 1);
                    if (body.length > BODY_LIMIT) {
                      request.cancel();
                      throw new FetchFailure("Feed exceeds the 3 MiB limit", null);
                    }
                  } else {
                    request.cancel(); // No redirect/error response body is needed.
                  }
                  return new Payload(
                      r.getCode(),
                      body,
                      header(r, "ETag"),
                      header(r, "Last-Modified"),
                      header(r, "Location"),
                      header(r, "Retry-After"));
                });
      } catch (FetchFailure e) {
        throw e;
      } catch (IOException e) {
        if (retries >= 2) throw new FetchFailure("Publisher connection failed", null);
        pause(retries++);
        continue;
      } finally {
        timer.cancel(false);
      }

      if (response.status == 200 || response.status == 304) return response;
      if (Set.of(301, 302, 303, 307, 308).contains(response.status)
          && redirects++ < 3
          && response.redirect != null) {
        uri = validate(uri.resolve(response.redirect).toString());
        if ((articlePage || BUILTIN_FEEDS.contains(value))
            && !uri.getHost().equals(origin.getHost()))
          throw new FetchFailure("Unexpected official feed redirect", null);
        continue;
      }
      if (response.status == 429 || response.status == 503 && response.retryAfter != null) {
        throw new FetchFailure(
            "Publisher requested a later fetch (HTTP " + response.status + ")",
            retryAt(response.retryAfter));
      }
      if (response.status >= 500 && retries < 2) {
        pause(retries++);
        continue;
      }
      throw new FetchFailure("Publisher returned HTTP " + response.status, null);
    }
    throw new FetchFailure("Publisher request timed out", null);
  }

  private static void pause(int retry) throws IOException {
    try {
      Thread.sleep(200L << retry);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted");
    }
  }

  static Instant retryAt(String header) {
    Instant now = Instant.now();
    if (header != null) {
      try {
        return now.plusSeconds(Math.clamp(Long.parseLong(header.trim()), 60L, 31_536_000L));
      } catch (RuntimeException ignored) {
        try {
          Instant date =
              ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
          if (date.isAfter(now)) return date;
        } catch (RuntimeException invalidDate) {
          /* use the normal polling interval */
        }
      }
    }
    return now.plus(Duration.ofHours(1));
  }

  private static String header(HttpResponse response, String name) {
    Header header = response.getFirstHeader(name);
    if (header == null || header.getValue().length() > 2048) return null;
    return header.getValue();
  }

  private static void conditional(HttpGet request, String name, String value) {
    if (value != null && value.length() < 2048 && value.chars().allMatch(c -> c >= 32 && c < 127)) {
      request.setHeader(name, value);
    }
  }

  @PreDestroy
  public void close() throws IOException {
    deadlines.shutdownNow();
    http.close();
  }
}
