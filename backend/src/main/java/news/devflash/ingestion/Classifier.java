package news.devflash.ingestion;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

final class Classifier {
  private static final Pattern PAYMENT_TECH =
      words(
          "FedNow|Fedwire|fintech|stablecoins?|payment systems?|payment accounts?|payments?"
              + " (?:services?|infrastructure|systems?|processing|networks?|innovation|technology)|digital"
              + " assets?|crypto-assets?|tokeni[sz](?:ation|ed)|instant payments?|triennial"
              + " payments study");

  static boolean isFintechAnnouncement(String title) {
    return PAYMENT_TECH.matcher(title).find();
  }

  private static final Map<String, Pattern> CATEGORIES =
      Map.of(
          "ai",
              words(
                  "AI|ML|LLM|LLMs|artificial intelligence|generative|neural|machine"
                      + " learning|inference|foundation model|language model|transformer"),
          "security",
              words(
                  "cybersecurity|security|ransomware|breach|vulnerability|vulnerabilities|CVE|malware|advisory|exploit|phishing"),
          "new-tech",
              words(
                  "chip|processor|hardware|smartphone|macOS|laptop|operating"
                      + " system|smartwatch|semiconductor|quantum|robotics"),
          "developer",
              words(
                  "developer|Kubernetes|Go|coding|Rust|Java|API|APIs|compiler|database|GitHub|framework|open"
                      + " source|Docker|software|SDK"),
          "fintech",
              words(
                  "fintech|payments?|banking|digital wallet|blockchain|payment processing|open"
                      + " banking"));
  private static final List<String> TOPICS =
      List.of(
          "Kubernetes",
          "Go",
          "Linux",
          "PostgreSQL",
          "OpenAI",
          "GitHub",
          "Apple",
          "Google",
          "Python",
          "Rust",
          "Java",
          "Spring Boot",
          "React",
          "TypeScript",
          "Microsoft",
          "Cybersecurity",
          "Banking technology",
          "Docker",
          "Cloudflare",
          "WebRTC",
          "Ransomware",
          "Digital wallets",
          "Machine learning");
  private static final Pattern HOLD =
      words("sponsored|advertorial|rumou?r|unconfirmed|giveaway|coupon");

  record Result(Map<String, Double> categories, List<String> topics, boolean needsReview) {}

  static Result classify(FeedParser.Story story, String source) {
    String title = story.title(), tags = story.tags(), description = story.preview();
    var categories = new LinkedHashMap<String, Double>();
    CATEGORIES.forEach(
        (key, rule) -> {
          double score =
              (rule.matcher(title).find() ? .68 : 0)
                  + (rule.matcher(tags).find() ? .35 : 0)
                  + (rule.matcher(description).find() ? .26 : 0);
          if (key.equals("developer")
              && (source.equals("The Go Blog") || source.equals("Kubernetes Blog"))) score += .65;
          if (key.equals("fintech") && isFintechAnnouncement(title)) score = Math.max(score, .68);
          if (score >= .26) categories.put(key, Math.min(.99, score));
        });
    List<String> topics =
        TOPICS.stream()
            .filter(
                topic ->
                    words(Pattern.quote(topic)).matcher(title + " " + tags + " " + source).find())
            .toList();
    return new Result(
        categories, topics, categories.isEmpty() || HOLD.matcher(title + " " + tags).find());
  }

  static String slug(String s) {
    String normalized =
        Normalizer.normalize(s, Normalizer.Form.NFKD)
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\p{M}+", "")
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-|-$", "");
    return normalized.isEmpty()
        ? "article"
        : normalized.substring(0, Math.min(normalized.length(), 75));
  }

  private static Pattern words(String values) {
    return Pattern.compile("\\b(?:" + values + ")\\b", Pattern.CASE_INSENSITIVE);
  }

  private Classifier() {}
}
