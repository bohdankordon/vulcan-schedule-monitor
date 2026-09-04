package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VerifiedVulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanBrowserAuthenticator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionVerifier;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.JpaVulcanConnectTokenService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectLink;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "vulcan.connection.enabled=true",
      "vulcan.connection.public-base-url=https://connect.example/",
      "vulcan.connection.token-ttl=PT10M",
      "vulcan.connection.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@AutoConfigureMockMvc
@Import(VulcanConnectControllerTests.Fakes.class)
class VulcanConnectControllerTests extends PostgresIntegrationTestSupport {

  private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TelegramIdentityRegistration identities;
  @Autowired private JpaVulcanConnectTokenService tokens;
  @Autowired private Fakes fakes;
  @Autowired private MutableClock clock;

  @BeforeEach
  void reset() {
    clearDatabase();
    clock.instant = NOW;
    fakes.failure.set(null);
    fakes.authenticationCalls = 0;
  }

  @AfterEach
  void cleanup() {
    clearDatabase();
  }

  @Test
  void initialGetExchangesTokenForSecureStrictHttpOnlyCookieAndRedirects() throws Exception {
    String raw = issue();

    mvc.perform(get("/connect/{token}", raw))
        .andExpect(status().isSeeOther())
        .andExpect(redirectedUrl("/connect"))
        .andExpect(
            header()
                .string(
                    HttpHeaders.SET_COOKIE,
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString(
                            VulcanConnectController.TOKEN_COOKIE + "="),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Path=/connect"))))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void validCookieRendersSelfContainedCsrfFormWithDefensiveHeaders() throws Exception {
    String raw = issue();

    mvc.perform(get("/connect").cookie(cookie(raw)))
        .andExpect(status().isOk())
        .andExpect(view().name("connect-form"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("type=\"password\"")))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Referrer-Policy", "no-referrer"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "DENY"))
        .andExpect(header().exists("Content-Security-Policy"));
  }

  @Test
  void missingExpiredAndWrongCookieNeverRenderCredentialForm() throws Exception {
    mvc.perform(get("/connect"))
        .andExpect(status().isOk())
        .andExpect(view().name("connect-unavailable"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("type=\"password\""))));

    String raw = issue();
    clock.instant = NOW.plusSeconds(601);
    mvc.perform(get("/connect").cookie(cookie(raw))).andExpect(view().name("connect-unavailable"));
    mvc.perform(get("/connect").cookie(cookie("___________________________________________")))
        .andExpect(view().name("connect-unavailable"));
  }

  @Test
  void credentialPostRequiresValidCsrf() throws Exception {
    String raw = issue();

    mvc.perform(
            post("/connect")
                .cookie(cookie(raw))
                .param("portalUrl", "https://school.vulcan.net.pl/tenant/")
                .param("login", "synthetic-login")
                .param("password", "synthetic-password"))
        .andExpect(status().isForbidden());

    assertThat(fakes.authenticationCalls).isZero();
  }

  @Test
  void failedLoginNeverReflectsPasswordAndAllowsRetry() throws Exception {
    String raw = issue();
    String passwordMarker = "must-not-appear-78e1";
    fakes.failure.set(VulcanAuthFailureCategory.INVALID_CREDENTIALS);

    mvc.perform(
            post("/connect")
                .with(csrf())
                .cookie(cookie(raw))
                .param("portalUrl", "https://school.vulcan.net.pl/tenant/")
                .param("login", "synthetic-login")
                .param("password", passwordMarker))
        .andExpect(status().isOk())
        .andExpect(view().name("connect-form"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(passwordMarker))))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("not accepted")));
  }

  @Test
  void successfulPostClearsCookieAndShowsOnlySafeCount() throws Exception {
    String raw = issue();

    mvc.perform(
            post("/connect")
                .with(csrf())
                .cookie(cookie(raw))
                .param("portalUrl", "https://school.vulcan.net.pl/tenant/")
                .param("login", "synthetic-login")
                .param("password", "synthetic-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("connect-success"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString(
                        "Available classes found: <span>1</span>")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Synthetic Class"))))
        .andExpect(
            header()
                .string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.containsString("Max-Age=0")));
  }

  @Test
  void rejectedPortalUrlDoesNotReachAuthenticator() throws Exception {
    String raw = issue();

    mvc.perform(
            post("/connect")
                .with(csrf())
                .cookie(cookie(raw))
                .param("portalUrl", "https://127.0.0.1/")
                .param("login", "synthetic-login")
                .param("password", "synthetic-password"))
        .andExpect(view().name("connect-form"));

    assertThat(fakes.authenticationCalls).isZero();
  }

  private String issue() {
    long userId = identities.registerOrUpdate(8_200_000_001L, 9_200_000_001L).id();
    ConnectLink link = tokens.issue(userId);
    String path = URI.create(link.url()).getPath();
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static Cookie cookie(String raw) {
    return new Cookie(VulcanConnectController.TOKEN_COOKIE, raw);
  }

  private void clearDatabase() {
    jdbc.update("DELETE FROM vulcan_class_catalog");
    jdbc.update("DELETE FROM vulcan_account_secret");
    jdbc.update("DELETE FROM vulcan_connect_token");
    jdbc.update("DELETE FROM vulcan_account");
    jdbc.update("DELETE FROM notification_outbox");
    jdbc.update("DELETE FROM monitoring_subscription");
    jdbc.update("DELETE FROM telegram_identity");
    jdbc.update("DELETE FROM app_user");
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {
    private final AtomicReference<VulcanAuthFailureCategory> failure = new AtomicReference<>();
    private int authenticationCalls;

    @Bean
    @Primary
    VulcanBrowserAuthenticator fakeAuthenticator() {
      return request -> {
        authenticationCalls++;
        if (failure.get() != null) {
          throw new VulcanAuthenticationException(failure.get());
        }
        URI base = URI.create("https://school.vulcan.net.pl/tenant/unit/");
        return new VulcanSessionMaterial(base, base, "verification", "guid", "unknown=cookie");
      };
    }

    @Bean
    @Primary
    VulcanSessionVerifier fakeVerifier() {
      return material ->
          new VerifiedVulcanSession(
              material,
              List.of(
                  new SchoolClass(
                      7101,
                      8101,
                      "Synthetic Class",
                      "UNIT",
                      1,
                      2026,
                      LocalDate.of(2026, 9, 1),
                      LocalDate.of(2027, 6, 30))));
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock();
    }
  }

  static final class MutableClock extends Clock {
    private volatile Instant instant = NOW;

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
