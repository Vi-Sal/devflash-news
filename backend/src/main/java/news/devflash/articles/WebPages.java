package news.devflash.articles;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.ObjectMapper;

@Controller
class WebPages {
  private final NewsQueries news;
  private final ArticleRepository articles;
  private final ObjectMapper mapper;
  private final String base;

  WebPages(
      NewsQueries news,
      ArticleRepository articles,
      ObjectMapper mapper,
      @Value("${APP_BASE_URL:${RENDER_EXTERNAL_URL:http://localhost:8080}}") String base) {
    this.news = news;
    this.articles = articles;
    this.mapper = mapper;
    this.base = base.replaceAll("/+$", "");
    var uri = URI.create(this.base);
    if (uri.getHost() == null
        || !Set.of("https", "http").contains(uri.getScheme())
        || uri.getUserInfo() != null) throw new IllegalArgumentException("Invalid APP_BASE_URL");
  }

  @GetMapping({
    "/",
    "/saved",
    "/search",
    "/explore",
    "/daily",
    "/sources",
    "/trending",
    "/about",
    "/privacy",
    "/terms",
    "/admin",
    "/admin/**",
    "/category/{slug}",
    "/topics/{slug}"
  })
  String spa() {
    return "forward:/index.html";
  }

  @GetMapping("/404")
  @ResponseBody
  ResponseEntity<String> missing() throws IOException {
    return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML).body(template());
  }

  String template() throws IOException {
    return new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
  }

  @GetMapping(value = "/article/{slug}", produces = "text/html")
  @ResponseBody
  ResponseEntity<String> story(@PathVariable String slug) throws IOException {
    NewsQueries.Story story;
    try {
      story = news.one(slug);
    } catch (ResponseStatusException e) {
      if (e.getStatusCode().value() == 404) return missing();
      throw e;
    }
    var doc = Jsoup.parse(template());
    String url = base + "/article/" + story.slug(),
        title = story.title() + " · DevFlash",
        desc = Objects.toString(story.summary(), "");
    doc.title(title);
    doc.select("meta[name=description]").attr("content", desc);
    doc.head().appendElement("link").attr("rel", "canonical").attr("href", url);
    String artworkCategory =
        story.categories().stream()
            .filter(Set.of("ai", "security", "developer", "new-tech", "fintech")::contains)
            .findFirst()
            .orElse("new-tech");
    String socialImage =
        Objects.requireNonNullElse(story.imageUrl(), base + "/art/" + artworkCategory + ".jpg");
    var tags =
        Map.of(
            "og:type",
            "article",
            "og:url",
            url,
            "og:title",
            story.title(),
            "og:description",
            desc,
            "og:image",
            socialImage);
    tags.forEach(
        (name, value) ->
            doc.head().appendElement("meta").attr("property", name).attr("content", value));
    var json =
        Map.of(
            "@context",
            "https://schema.org",
            "@type",
            "NewsArticle",
            "headline",
            story.title(),
            "description",
            desc,
            "datePublished",
            story.publishedAt().toString(),
            "url",
            url,
            "citation",
            story.originalUrl(),
            "publisher",
            Map.of("@type", "Organization", "name", "DevFlash"));
    doc.head()
        .appendElement("script")
        .attr("type", "application/ld+json")
        .appendChild(new DataNode(mapper.writeValueAsString(json).replace("<", "\\u003c")));
    var main = doc.selectFirst("#root").appendElement("main");
    main.appendElement("h1").text(story.title());
    main.appendElement("p").text(desc);
    main.appendElement("a").attr("href", story.originalUrl()).text(story.source());
    return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(doc.outerHtml());
  }

  @GetMapping(value = "/robots.txt", produces = "text/plain")
  @ResponseBody
  String robots() {
    return "User-agent: *\nDisallow: /admin\nDisallow: /api/\nSitemap: " + base + "/sitemap.xml\n";
  }

  @GetMapping(value = "/sitemap.xml", produces = "application/xml")
  @ResponseBody
  String sitemap() {
    var xml =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><urlset"
                + " xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
    for (String path : List.of("/", "/explore", "/trending", "/daily", "/sources", "/about"))
      add(xml, path);
    for (var a :
        articles.findByPublicationStatusOrderByPublishedAtDescIdDesc(
            "PUBLISHED", PageRequest.of(0, 5000))) add(xml, "/article/" + a.slug);
    return xml.append("</urlset>").toString();
  }

  private void add(StringBuilder xml, String path) {
    xml.append("<url><loc>").append(HtmlUtils.htmlEscape(base + path)).append("</loc></url>");
  }
}
