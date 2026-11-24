package news.devflash.ingestion;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.io.SyndFeedInput;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class FeedParser {
  public record Story(
      String title,
      String original,
      String canonical,
      String preview,
      String author,
      Instant published,
      String tags,
      String image,
      Advisory advisory,
      String itemId,
      String imageSource) {
    Story(
        String title,
        String original,
        String canonical,
        String preview,
        String author,
        Instant published,
        String tags,
        String image,
        Advisory advisory) {
      this(
          title, original, canonical, preview, author, published, tags, image, advisory, null,
          null);
    }
  }

  record Advisory(String cve, String vendor, String product, String mitigation) {}

  record Parsed(List<Story> stories, int skipped) {}

  private final ObjectMapper json;

  public FeedParser(ObjectMapper json) {
    this.json = json;
  }

  Parsed parse(byte[] bytes, String type) {
    if (bytes.length > FeedClient.BODY_LIMIT)
      throw new IllegalArgumentException("Feed is too large");
    try {
      List<Story> stories = new ArrayList<>();
      int count = 0;
      if ("CISA_KEV".equals(type)) return parseCatalogue(bytes);
      if ("JSON_FEED".equalsIgnoreCase(type)) {
        JsonNode feed = json.readTree(bytes);
        if (feed == null
            || !Set.of("https://jsonfeed.org/version/1", "https://jsonfeed.org/version/1.1")
                .contains(feed.path("version").asText())
            || !feed.path("items").isArray()) {
          throw new IllegalArgumentException("Unsupported JSON Feed");
        }
        for (JsonNode item : feed.path("items")) {
          if (++count > 1000) throw new IllegalArgumentException("Too many feed entries");
          JsonNode author = item.path("authors").path(0);
          if (author.isMissingNode()) author = item.path("author");
          List<String> tags = new ArrayList<>();
          item.path("tags").forEach(tag -> tags.add(tag.asText()));
          String preview = item.path("summary").asText(item.path("content_text").asText(""));
          add(
              stories,
              item.path("title").asText(),
              item.path("url").asText(),
              preview,
              author.path("name").asText(),
              date(item.path("date_published").asText()),
              String.join(" ", tags),
              item.path("image").asText(),
              item.path("id").asText(),
              "API");
        }
      } else {
        inspectXml(bytes);
        var reader = new SyndFeedInput();
        reader.setAllowDoctypes(false);
        var feed = reader.build(new InputSource(new ByteArrayInputStream(bytes)));
        for (SyndEntry entry : feed.getEntries()) {
          if (++count > 1000) throw new IllegalArgumentException("Too many feed entries");
          String author =
              entry.getAuthors().stream()
                  .map(person -> person.getName())
                  .filter(Objects::nonNull)
                  .distinct()
                  .reduce((a, b) -> a + ", " + b)
                  .orElse(entry.getAuthor());
          String tags =
              String.join(
                  " ",
                  entry.getCategories().stream()
                      .map(SyndCategory::getName)
                      .filter(Objects::nonNull)
                      .toList());
          String image =
              entry.getEnclosures().stream()
                  .filter(
                      e ->
                          Set.of("image/jpeg", "image/png", "image/webp")
                              .contains(Objects.toString(e.getType(), "")))
                  .map(e -> e.getUrl())
                  .filter(Objects::nonNull)
                  .findFirst()
                  .orElse(null);
          String kind = "ENCLOSURE";
          image = imageUrl(entry.getLink(), image);
          for (String tag : List.of("content", "thumbnail")) {
            String candidate = media(entry.getForeignMarkup(), tag, entry.getLink());
            if (candidate != null) {
              image = candidate;
              kind = "MEDIA_" + tag.toUpperCase(Locale.ROOT);
              break;
            }
          }
          add(
              stories,
              entry.getTitle(),
              entry.getLink(),
              entry.getDescription() == null ? "" : entry.getDescription().getValue(),
              author,
              entry.getPublishedDate() == null ? null : entry.getPublishedDate().toInstant(),
              tags,
              image,
              entry.getUri(),
              kind);
        }
      }
      return new Parsed(List.copyOf(stories), count - stories.size());
    } catch (Exception invalid) {
      throw new IllegalArgumentException("Publisher returned an invalid or unsupported feed");
    }
  }

  private Parsed parseCatalogue(byte[] bytes) throws Exception {
    var catalog = json.readTree(bytes);
    var entries = catalog.path("vulnerabilities");
    if (!entries.isArray()
        || entries.size() != catalog.path("count").asInt(-1)
        || entries.size() > 20000) {
      throw new IllegalArgumentException("Invalid KEV catalogue");
    }
    var output = new ArrayList<Story>();
    var cutoff = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(30);
    int skipped = 0;
    for (var entry : entries) {
      var day = java.time.LocalDate.parse(entry.path("dateAdded").asText());
      if (day.isBefore(cutoff)) continue;
      String cve = entry.path("cveID").asText("");
      String title = plain(entry.path("vulnerabilityName").asText(), 450);
      String preview = plain(entry.path("shortDescription").asText(), 500);
      Instant date = day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
      if (!cve.matches("CVE-[0-9]{4}-[0-9]{4,19}")
          || title.isEmpty()
          || date.isAfter(Instant.now())) {
        skipped++;
        continue;
      }
      String url = "https://www.cisa.gov/known-exploited-vulnerabilities-catalog?search=" + cve;
      Advisory advisory =
          new Advisory(
              cve,
              plain(entry.path("vendorProject").asText(), 250),
              plain(entry.path("product").asText(), 250),
              plain(entry.path("requiredAction").asText(), 1000));
      output.add(
          new Story(title, url, url, preview, "CISA", date, "Security advisory", null, advisory));
    }
    output.sort(Comparator.comparing(Story::published).reversed().thenComparing(Story::canonical));
    return new Parsed(List.copyOf(output), skipped);
  }

  private static void inspectXml(byte[] bytes) throws Exception {
    var factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    var xml = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    int depth = 0, elements = 0;
    try {
      while (xml.hasNext()) {
        int event = xml.next();
        if (event == XMLStreamConstants.DTD
            || event == XMLStreamConstants.START_ELEMENT && (++depth > 64 || ++elements > 65_536))
          throw new IllegalArgumentException();
        if (event == XMLStreamConstants.END_ELEMENT) depth--;
      }
    } finally {
      xml.close();
    }
  }

  private static Instant date(String value) {
    try {
      return Instant.parse(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String media(List<org.jdom2.Element> nodes, String tag, String base) {
    for (var node : nodes) {
      if (!"http://search.yahoo.com/mrss/".equals(node.getNamespaceURI())) continue;
      if (node.getName().equals(tag)) {
        String type = node.getAttributeValue("type", ""),
            medium = node.getAttributeValue("medium", "");
        if ((medium.isEmpty() || medium.equals("image"))
            && (type.isEmpty() || type.startsWith("image/"))) {
          String url = imageUrl(base, node.getAttributeValue("url"));
          if (url != null) return url;
        }
      }
      if (node.getName().equals("group")) {
        String url = media(node.getChildren(), tag, base);
        if (url != null) return url;
      }
    }
    return null;
  }

  static String imageUrl(String base, String value) {
    if (value == null || value.isBlank() || base == null) return null;
    try {
      URI u = URI.create(base).resolve(value.strip()).normalize();
      return "https".equalsIgnoreCase(u.getScheme()) && canonical(u.toString()) != null
          ? u.toASCIIString()
          : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static void add(
      List<Story> output,
      String title,
      String url,
      String description,
      String author,
      Instant date,
      String tags,
      String image,
      String identifier,
      String imageSource) {
    if (date == null || date.isAfter(Instant.now()) || date.isBefore(Instant.EPOCH)) return;
    String cleanTitle = plain(title, 450);
    if (cleanTitle.isBlank()) return;
    String canonical = canonical(url);
    if (canonical == null) return;
    String safeImage = imageUrl(canonical, image);
    output.add(
        new Story(
            cleanTitle,
            url.strip(),
            canonical,
            plain(description, 500),
            plain(author, 250),
            date,
            plain(tags, 500),
            safeImage,
            null,
            identifier == null || identifier.isBlank() || identifier.length() > 1024
                ? null
                : identifier.strip(),
            safeImage == null ? null : imageSource));
  }

  static String plain(String html, int max) {
    if (html == null || html.isBlank()) return "";
    String text =
        Jsoup.parseBodyFragment(html.substring(0, Math.min(html.length(), 32768)))
            .text()
            .replaceAll("[\\p{Cntrl}\\p{Cf}]+", " ")
            .replaceAll("\\s+", " ")
            .strip();
    if (text.codePointCount(0, text.length()) <= max) return text;
    int cut = text.offsetByCodePoints(0, max - 1);
    int space = text.lastIndexOf(' ', cut);
    return text.substring(0, space > cut / 2 ? space : cut).stripTrailing() + "…";
  }

  static String canonical(String input) {
    if (input == null || input.length() > 2048) return null;
    try {
      URI u = URI.create(input.strip()).normalize();
      String scheme = Objects.toString(u.getScheme(), "").toLowerCase(Locale.ROOT);
      String host = Objects.toString(u.getHost(), "").toLowerCase(Locale.ROOT);
      if (!(scheme.equals("https") || scheme.equals("http"))
          || u.getUserInfo() != null
          || !host.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,63}")
          || Stream.of(".localhost", ".local", ".internal", ".test", ".invalid", ".onion")
              .anyMatch(host::endsWith)
          || u.getPort() != -1
              && !(scheme.equals("https") && u.getPort() == 443
                  || scheme.equals("http") && u.getPort() == 80)) {
        return null;
      }
      String path = u.getRawPath().isEmpty() ? "/" : u.getRawPath();
      List<String> query = new ArrayList<>();
      if (u.getRawQuery() != null) {
        for (String part : u.getRawQuery().split("&")) {
          String key =
              URLDecoder.decode(part.split("=", 2)[0], StandardCharsets.UTF_8)
                  .toLowerCase(Locale.ROOT);
          if (!part.isBlank()
              && !key.startsWith("utm_")
              && !Set.of("gclid", "fbclid", "mc_cid", "mc_eid").contains(key)) {
            query.add(part);
          }
        }
      }
      return scheme + "://" + host + path + (query.isEmpty() ? "" : "?" + String.join("&", query));
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }
}
