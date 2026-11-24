package news.devflash.summarization;

import java.io.IOException;
import java.util.Optional;

public interface ArticleSummarizationService {
  record Evidence(String title, String publisher, String excerpt) {}

  record Explanation(
      String whatHappened, String whyItMatters, String keyTakeaway, String developerImpact) {}

  record Result(Optional<Explanation> explanation, int inputTokens, int outputTokens) {
    static Result empty() {
      return new Result(Optional.empty(), 0, 0);
    }
  }

  boolean available();

  String provider();

  String model();

  Result summarize(Evidence evidence) throws IOException, InterruptedException;
}
