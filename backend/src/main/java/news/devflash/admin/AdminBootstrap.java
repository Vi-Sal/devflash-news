package news.devflash.admin;

import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AdminBootstrap implements ApplicationRunner {
  private final JdbcTemplate db;
  private final PasswordEncoder encoder;
  private final String username;
  private final String password;

  AdminBootstrap(
      JdbcTemplate db,
      PasswordEncoder encoder,
      @Value("${ADMIN_BOOTSTRAP_USERNAME:}") String username,
      @Value("${ADMIN_BOOTSTRAP_PASSWORD:}") String password) {
    this.db = db;
    this.encoder = encoder;
    this.username = username;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (username.isBlank() && password.isBlank()) return;
    db.execute("select pg_advisory_xact_lock(814033712)");
    if (Boolean.TRUE.equals(
        db.queryForObject("select exists(select 1 from admin_users)", Boolean.class))) return;
    if (!username.matches("[a-zA-Z0-9_.@-]{3,80}")
        || password.length() < 16
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new IllegalStateException(
          "Use a 3–80 character administrator name and a fresh secret of at least 16 characters and"
              + " at most 72 UTF-8 bytes");
    db.update(
        "insert into admin_users(username,password_hash,enabled) values(?,?,true)",
        username,
        encoder.encode(password));
    db.update(
        """
        insert into admin_audit_logs(admin_user_id,action,entity_type,safe_metadata)
        select id,'bootstrap','administrator','{}'::jsonb from admin_users where username=?
        """,
        username);
  }
}
