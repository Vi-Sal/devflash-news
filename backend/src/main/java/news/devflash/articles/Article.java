package news.devflash.articles;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "articles")
public class Article {
  @Id Long id;
  String slug;
  String title;
  String summary;
  String publicationStatus;
  Instant publishedAt;
}
