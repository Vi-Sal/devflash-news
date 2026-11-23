package news.devflash.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class FeedPipelineTest {
  private final FeedParser parser = new FeedParser(new ObjectMapper());

  @Test
  void rssReadsSourceMetadataAndRemovesMarkup() {
    var parsed =
        parse(
            """
            <rss version="2.0"><channel><title>Test publisher</title><link>https://example.org/</link><description>Fixture</description>
            <item><title>Test developer update</title><link>https://example.org/story?id=1&amp;utm_medium=rss</link>
            <pubDate>Tue, 10 Nov 2020 00:00:00 GMT</pubDate>
            <description><![CDATA[<p>Publisher <b>preview</b>.</p><script>hidden</script>]]></description>
            <category>Developer</category></item></channel></rss>\
            """,
            "RSS");
    assertThat(parsed.stories()).hasSize(1);
    var story = parsed.stories().getFirst();
    assertThat(story.preview()).isEqualTo("Publisher preview.");
    assertThat(story.canonical()).isEqualTo("https://example.org/story?id=1");
    assertThat(story.tags()).isEqualTo("Developer");
    assertThat(story.published()).isEqualTo(Instant.parse("2020-11-10T00:00:00Z"));
  }

  @Test
  void atomKeepsBylineAndSummary() {
    var parsed =
        parse(
            """
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Fixture</title><id>urn:test</id><updated>2020-01-01T00:00:00Z</updated>
            <entry><id>urn:item</id><title>News</title><published>2020-01-01T00:00:00Z</published><updated>2020-01-02T00:00:00Z</updated>
            <link href="https://example.org/news"/><author><name>Editor</name></author><summary>Limited preview</summary></entry></feed>\
            """,
            "ATOM");
    assertThat(parsed.stories().getFirst().author()).isEqualTo("Editor");
    assertThat(parsed.stories().getFirst().preview()).isEqualTo("Limited preview");
  }

  @Test
  void jsonFeedRejectsUnsafeLinksAndMissingDates() {
    var parsed =
        parse(
            """
            {"version":"https://jsonfeed.org/version/1.1","items":[
            {"title":"Fixture","date_published":"2020-01-01T00:00:00Z","url":"https://example.org/a"},
            {"title":"Undated","url":"https://example.org/b"},
            {"title":"Invalid","date_published":"2020-01-01T00:00:00Z","url":"javascript:alert(1)"}]}
            """,
            "JSON_FEED");
    assertThat(parsed.stories()).hasSize(1);
    assertThat(parsed.skipped()).isEqualTo(2);
  }

  @Test
  void refusesDoctypesAndOversizedTrees() {
    assertThatThrownBy(() -> parse("<!DOCTYPE rss><rss/>", "RSS"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parse("<a>".repeat(70) + "</a>".repeat(70), "ATOM"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void urlKeysRemoveTrackingWithoutChangingEncodedPublisherPath() {
    String link = "HTTPS://EXAMPLE.org:443/a/../pkg%2Fb?id=5&utm_source=feed&x=%2B#here";
    assertThat(FeedParser.canonical(link)).isEqualTo("https://example.org/pkg%2Fb?id=5&x=%2B");
    assertThat(FeedParser.canonical("https://user@example.org/a")).isNull();
  }

  @Test
  void categoriesCanOverlapAndWeakItemsAreHeld() {
    var story =
        new FeedParser.Story(
            "Security advisory for an AI model",
            "",
            "",
            "",
            "",
            Instant.EPOCH,
            "Developer",
            null,
            null);
    var result = Classifier.classify(story, "Publisher");
    assertThat(result.categories()).containsKeys("ai", "developer", "security");
    assertThat(result.needsReview()).isFalse();
    var unknown =
        new FeedParser.Story("Neighborhood roundup", "", "", "", "", Instant.EPOCH, "", null, null);
    assertThat(Classifier.classify(unknown, "Publisher").needsReview()).isTrue();
  }

  @Test
  void feedUrlRequiresExactHttpsHostAndDefaultPort() throws Exception {
    var client = new FeedClient("go.dev,kubernetes.io");
    try {
      assertThat(client.validate("https://go.dev/blog/feed.atom").getHost()).isEqualTo("go.dev");
      for (String value :
          List.of(
              "http://go.dev/x",
              "https://evil.go.dev/x",
              "https://go.dev:2222/x",
              "https://user@go.dev/blog",
              "https://go.dev./feed",
              "file:///tmp/local")) {
        assertThatThrownBy(() -> client.validate(value))
            .isInstanceOf(IllegalArgumentException.class);
      }
    } finally {
      client.close();
    }
  }

  @Test
  void connectorRejectsAllAnswersIfAnyAddressIsProhibited() throws Exception {
    var good = InetAddress.getByName("8.8.4.4");
    for (String address :
        List.of(
            "0.0.0.1",
            "10.0.0.1",
            "100.64.5.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.20.1.2",
            "192.168.1.2",
            "192.0.2.1",
            "198.18.1.2",
            "203.0.113.1",
            "::1",
            "2600::ffff:0:1",
            "fc00::1",
            "2002::1",
            "64:ff9b::7f00:1")) {
      // Ordinary global IPv6 is accepted, alongside ordinary global IPv4.
      var a = InetAddress.getByName(address);
      if (address.startsWith("2600:"))
        assertThat(FeedClient.checkedAddresses(new InetAddress[] {good, a})).hasSize(2);
      else
        assertThatThrownBy(() -> FeedClient.checkedAddresses(new InetAddress[] {good, a}))
            .isInstanceOf(UnknownHostException.class);
    }
  }

  @Test
  void schedulerRequiresAnIndependentConfiguredToken() {
    var svc = mock(IngestionService.class);
    assertThatThrownBy(() -> new SchedulerController(svc, "").run("Bearer demo"))
        .isInstanceOf(ResponseStatusException.class);
    var endpoint = new SchedulerController(svc, "isolated-test-token");
    assertThatThrownBy(() -> endpoint.run("Bearer incorrect"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> endpoint.run(null)).isInstanceOf(ResponseStatusException.class);
    verifyNoInteractions(svc);
    when(svc.run(null)).thenReturn(new IngestionService.RunReport(0, 0, 0, 0));
    assertThat(endpoint.run("Bearer isolated-test-token")).containsKey("data");
  }

  private FeedParser.Parsed parse(String value, String type) {
    return parser.parse(value.getBytes(StandardCharsets.UTF_8), type);
  }
}
