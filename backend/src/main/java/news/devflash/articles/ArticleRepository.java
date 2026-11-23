package news.devflash.articles;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ArticleRepository extends JpaRepository<Article, Long> {
  List<Article> findByPublicationStatusOrderByPublishedAtDescIdDesc(String status, Pageable page);
}
