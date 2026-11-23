package news.devflash.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminAudit {
  private final JdbcTemplate db;

  AdminAudit(JdbcTemplate db) {
    this.db = db;
  }

  void record(String username, String action, String entity, Long id) {
    db.update(
        """
        insert into admin_audit_logs(admin_user_id,action,entity_type,entity_id,safe_metadata)
        select id,?,?,?,'{}'::jsonb from admin_users where username=?
        """,
        action,
        entity,
        id == null ? null : id.toString(),
        username);
  }

  @Transactional
  public void signIn(String username) {
    db.update("update admin_users set last_login_at=now() where username=?", username);
    record(username, "login", "session", null);
  }
}
