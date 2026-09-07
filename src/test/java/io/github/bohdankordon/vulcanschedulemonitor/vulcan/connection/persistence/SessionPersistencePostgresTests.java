package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionMaterialTestSupport.cookiePairs;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionManager;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule.PersistedAccountWeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Production persistence/source regression with synthetic rows and loopback cookies only. */
@SpringJUnitConfig(SessionPersistenceTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(
    properties = {
      "vulcan.connection.enabled=true",
      "vulcan.connection.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "vulcan.monitoring.enabled=false",
      "telegram.bot.enabled=false",
      "spring.jpa.open-in-view=false",
      "spring.datasource.hikari.maximum-pool-size=2",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
class SessionPersistencePostgresTests extends PostgresIntegrationTestSupport {
  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate jdbc;
  HttpServer server;
  URI base;
  String responseCookie;
  final AtomicInteger calls = new AtomicInteger();
  final List<String> cookies = new ArrayList<>();
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-07T09:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void fixture() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try (exchange) {
            cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            if (exchange.getRequestMethod().equals("GET")) {
              exchange.sendResponseHeaders(204, -1);
              return;
            }
            byte[] body =
                "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Set-Cookie", responseCookie);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_TENANT/");
    responseCookie = "original=SUPER_SECRET_COOKIE_B; Path=/";
    jdbc.execute("TRUNCATE app_user CASCADE");
    jdbc.update("INSERT INTO app_user(id,created_at,updated_at) VALUES(1,now(),now())");
    jdbc.update(
        "INSERT INTO vulcan_account(id,app_user_id,status,created_at,updated_at) VALUES(1,1,'CONNECTED',now(),now())");
    context
        .getBean(VulcanSecretStore.class)
        .replace(1, material("original=SUPER_SECRET_COOKIE_A"), null, CLOCK.instant());
  }

  @AfterEach
  void cleanup() {
    if (server != null) server.stop(0);
    jdbc.execute("TRUNCATE app_user CASCADE");
  }

  VulcanSessionMaterial material(String cookie) {
    return new VulcanSessionMaterial(
        base, base, "SUPER_SECRET_TOKEN", "SUPER_SECRET_APPGUID", cookie);
  }

  @Test
  void currentAndNextProductionFetchesReloadBothCookieIdentitiesWithoutRetry() {
    var sessions = context.getBean(VulcanSessionManager.class);
    var source = new PersistedAccountWeeklyScheduleSource(sessions);
    LocalDate current = LocalDate.of(2026, 9, 7);
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    tx.executeWithoutResult(
        status -> {
          for (LocalDate week : List.of(current, current.plusWeeks(1))) {
            var scope = new TrackingScope(1, 1, 101, week, week.plusDays(6));
            assertThat(scope.matches(source.fetchCompleteWeeklySnapshot(scope))).isTrue();
            var em = context.getBean(jakarta.persistence.EntityManager.class);
            em.flush();
            em.clear();
            var saved = context.getBean(VulcanSecretStore.class).loadSession(1);
            assertThat(saved.cookieRepresentation())
                .isEqualTo(VulcanSessionMaterial.CookieRepresentation.STRUCTURED);
            assertThat(saved.cookieCount()).isEqualTo(2);
            assertThat(
                    SessionMaterialTestSupport.compare(
                            saved, sessions.loadCurrent(1).snapshotMaterial())
                        .allSame())
                .isTrue();
            assertThat(
                    SessionMaterialTestSupport.topology(sessions.loadCurrent(1))
                        .duplicateNameDifferentPathPresent())
                .isTrue();
          }
        });
    assertThat(cookies.getFirst().contains("SUPER_SECRET_COOKIE_B")).isFalse();
    assertThat(cookies.getLast().contains("original=SUPER_SECRET_COOKIE_A")).isTrue();
    assertThat(cookies.getLast().contains("original=SUPER_SECRET_COOKIE_B")).isTrue();
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void structuredCookiesRouteAfterRealResponseEncryptionPostgresReloadAndReconstruction()
      throws Exception {
    responseCookie = "original=SUPER_SECRET_COOKIE_B; Path=/; HttpOnly";
    var store = context.getBean(VulcanSecretStore.class);
    assertThat(store.loadSession(1).cookieRepresentation())
        .isEqualTo(VulcanSessionMaterial.CookieRepresentation.LEGACY_HEADER);
    var manager = context.getBean(VulcanSessionManager.class);
    var live = manager.loadCurrent(1);
    new VulcanClient(live).getWeekSchedule(101, LocalDate.of(2026, 9, 7));
    var expected = live.snapshotMaterial();
    assertThat(expected.cookieCount()).isEqualTo(2);
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    tx.executeWithoutResult(
        status -> {
          manager.replace(1, live);
          var em = context.getBean(jakarta.persistence.EntityManager.class);
          em.flush();
          em.clear();
          assertThat(SessionMaterialTestSupport.compare(expected, store.loadSession(1)).allSame())
              .isTrue();
        });
    var loaded = store.loadSession(1); // Separate transaction after flush/clear/commit.
    assertThat(loaded.cookieRepresentation())
        .isEqualTo(VulcanSessionMaterial.CookieRepresentation.STRUCTURED);
    assertThat(SessionMaterialTestSupport.compare(expected, loaded).allSame()).isTrue();
    var reconstructed = manager.loadCurrent(1);
    assertThat(
            SessionMaterialTestSupport.compare(expected, reconstructed.snapshotMaterial())
                .allSame())
        .isTrue();
    try (var client = reconstructed.configure(java.net.http.HttpClient.newBuilder()).build()) {
      for (URI uri :
          List.of(
              base.resolve("PlanLekcji.mvc/GetPlanLekcjiContext"), base.resolve("/root-check"))) {
        assertThat(
                client
                    .send(
                        java.net.http.HttpRequest.newBuilder(uri).build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode())
            .isEqualTo(204);
      }
    }
    String scheduleHeader = cookies.get(1), rootHeader = cookies.get(2);
    assertThat(scheduleHeader.contains("original=SUPER_SECRET_COOKIE_A")).isTrue();
    assertThat(scheduleHeader.contains("original=SUPER_SECRET_COOKIE_B")).isTrue();
    assertThat(
            scheduleHeader.indexOf("SUPER_SECRET_COOKIE_A")
                < scheduleHeader.indexOf("SUPER_SECRET_COOKIE_B"))
        .isTrue();
    assertThat(rootHeader.contains("original=SUPER_SECRET_COOKIE_B")).isTrue();
    assertThat(rootHeader.contains("SUPER_SECRET_COOKIE_A")).isFalse();
    assertThat(calls.get()).isEqualTo(3); // One rotation response, two route checks, no retry.
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void postgresPreservesExactMaterialBeforeReconstructionAndSafeMaterialAfterward(
      boolean duplicate) {
    var expected =
        material(
            "original=SUPER_SECRET_COOKIE_A;  "
                + (duplicate ? "original" : "rotated")
                + "=SUPER_SECRET_COOKIE_B");
    var store = context.getBean(VulcanSecretStore.class);
    var tx =
        new org.springframework.transaction.support.TransactionTemplate(
            context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    tx.executeWithoutResult(
        status -> {
          store.replace(1, expected, null, CLOCK.instant());
          var em = context.getBean(jakarta.persistence.EntityManager.class);
          em.flush();
          em.clear();
          var actual = store.loadSession(1);
          assertThat(SessionMaterialTestSupport.compare(expected, actual).allSame()).isTrue();
          assertThat(cookiePairs(expected).equals(cookiePairs(actual))).isTrue();
        });
    // A separate transaction/repository read still preserves exact material.
    var actual = store.loadSession(1);
    assertThat(SessionMaterialTestSupport.compare(expected, actual).allSame()).isTrue();
    assertThat(cookiePairs(expected).equals(cookiePairs(actual))).isTrue();
    var reconstructed =
        context.getBean(VulcanSessionManager.class).loadCurrent(1).snapshotMaterial();
    assertThat(SessionMaterialTestSupport.compare(expected, reconstructed).allSame())
        .isEqualTo(!duplicate);
    assertThat(calls.get()).isZero();
  }
}
