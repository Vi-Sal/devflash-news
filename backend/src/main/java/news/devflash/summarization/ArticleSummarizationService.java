package news.devflash.summarization;

import java.io.IOException;
import java.util.Optional;

public interface ArticleSummarizationService {
  record Evidence(String title, String publisher, String excerpt) {}

  record Explanation(
      String whatHappened, String whyItMatters, String keyTakeaway, String developerImpact) {}

  boolean available();

  String provider();

  String model();

  Optional<Explanation> summarize(Evidence evidence) throws IOException, InterruptedException;
}
