package news.devflash.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import news.devflash.ingestion.IngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminController.class)
@Import(AdminSecurity.class)
class AdminSecurityTest {
  @Autowired MockMvc mvc;
  @MockitoBean JdbcTemplate db;
  @MockitoBean AdminAudit audit;
  @MockitoBean AdminReads reads;
  @MockitoBean AdminEdits edits;
  @MockitoBean IngestionService ingestion;

  @Test
  void loginTokenIsPublicAndAdminDataRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/v1/admin/auth/csrf"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isString());
    mvc.perform(get("/api/v1/admin/auth/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/admin/articles"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/login"));
    mvc.perform(get("/api/v1/admin/auth/me").with(user("reader").roles("READER")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/auth/me").with(user("editor").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("editor"));
  }

  @Test
  void adminMutationsRequireCsrfEvenWhenAuthenticated() throws Exception {
    mvc.perform(delete("/api/v1/admin/articles/3").with(user("editor").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(delete("/api/v1/admin/articles/3").with(user("editor").roles("ADMIN")).with(csrf()))
        .andExpect(status().isNoContent());
    verify(edits).removeDuplicate("editor", 3L);
  }

  @Test
  void loginAndLogoutRequireCsrf() throws Exception {
    mvc.perform(
            post("/api/v1/admin/auth/login")
                .param("username", "nobody")
                .param("password", "incorrect"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/admin/auth/logout").with(user("editor").roles("ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void throttleLimitsAttemptsAndClearsOnSuccess() {
    var throttle = new AdminSecurity.LoginThrottle(Clock.systemUTC());
    for (int i = 0; i < 8; i++) assertThat(throttle.allow("client")).isTrue();
    assertThat(throttle.allow("client")).isFalse();
    assertThat(throttle.allow("second")).isTrue();
    throttle.clear("client");
    assertThat(throttle.allow("client")).isTrue();
  }

  @Test
  void validatesNonsecretSettings() {
    assertThat(AdminEdits.validSetting("youtubeUrl", "https://www.youtube.com/@DevFlash")).isTrue();
    assertThat(AdminEdits.validSetting("youtubeUrl", "https://youtube.com.example.org/@DevFlash"))
        .isFalse();
    assertThat(AdminEdits.validSetting("aiDailyBudget", "99999")).isFalse();
    assertThat(AdminEdits.validSetting("aiApiKey", "sensitive")).isFalse();
  }
}
