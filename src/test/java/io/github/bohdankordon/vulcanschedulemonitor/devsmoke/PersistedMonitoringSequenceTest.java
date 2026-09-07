package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistedMonitoringSequenceTest {
  final PostgreSQLContainer db = new PostgreSQLContainer("postgres:18.6");
  ConfigurableApplicationContext context;
  JdbcTemplate jdbc;
  HttpServer server;
  URI base;
  final AtomicInteger calls = new AtomicInteger();
  final List<String> cookies = new ArrayList<>();
  final List<Map<String, String>> forms = new ArrayList<>();
  final List<Duration> delays = new ArrayList<>();
  int[] statuses;
  String retryAfter;
  String responseCookie;
  boolean html;
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-06T22:30:00Z"), ZoneOffset.UTC);
  static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @BeforeAll
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        ex -> {
          int slot = calls.getAndIncrement();
          try (ex) {
            if (ex.getRequestMethod().equals("GET")) {
              cookies.add(ex.getRequestHeaders().getFirst("Cookie"));
              ex.sendResponseHeaders(204, -1);
              return;
            }
            assertThat(ex.getRequestMethod()).isEqualTo("POST");
            assertThat(ex.getRequestURI().getPath())
                .isEqualTo("/SECRET_TENANT/PlanLekcji.mvc/GetPlanLekcjiContext");
            assertThat(ex.getRequestHeaders().getFirst("Accept")).isNull();
            cookies.add(ex.getRequestHeaders().getFirst("Cookie"));
            forms.add(
                Schedule429Structure.formValues(
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            int status = statuses[Math.min(slot, statuses.length - 1)];
            ex.getResponseHeaders().set("Content-Type", html ? "text/html" : "application/json");
            ex.getResponseHeaders().set("Set-Cookie", responseCookie);
            if (status == 302) ex.getResponseHeaders().set("Location", "/never-follow");
            if (retryAfter != null) ex.getResponseHeaders().set("Retry-After", retryAfter);
            byte[] body =
                (html
                        ? "<html>SUPER_SECRET_TOKEN</html>"
                        : "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
          }
        });
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_TENANT/");
    db.start();
    Flyway.configure()
        .dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
        .load()
        .migrate();
    context =
        VulcanPersistedSessionJavaBaseline.open(
            db.getJdbcUrl(), db.getUsername(), db.getPassword(), KEY, false);
    jdbc = context.getBean(JdbcTemplate.class);
    assertThat(calls.get()).isZero();
  }

  @AfterAll
  void close() {
    if (context != null) context.close();
    if (server != null) server.stop(0);
    db.stop();
  }

  @BeforeEach
  void fixture() {
    jdbc.execute("TRUNCATE app_user CASCADE");
    jdbc.update("INSERT INTO app_user(id,created_at,updated_at) VALUES(1,now(),now())");
    jdbc.update(
        "INSERT INTO vulcan_account(id,app_user_id,status,created_at,updated_at) VALUES(1,1,'CONNECTED',now(),now())");
    target(1);
    context
        .getBean(VulcanSecretStore.class)
        .replace(1, material("original=SUPER_SECRET_COOKIE_A"), null, CLOCK.instant());
    calls.set(0);
    cookies.clear();
    forms.clear();
    delays.clear();
    statuses = new int[] {200, 200};
    retryAfter = null;
    responseCookie = "rotated=SUPER_SECRET_COOKIE_B; Path=/SECRET_TENANT/";
    html = false;
  }

  void target(long id) {
    jdbc.update(
        "INSERT INTO vulcan_class_catalog(id,vulcan_account_id,journal_id,class_id,name,school_year,synced_at) VALUES(?,1,?,?,'SECRET_CLASS',2026,now())",
        id,
        100 + id,
        id);
    jdbc.update(
        "INSERT INTO monitoring_subscription(app_user_id,catalog_class_id,enabled,created_at,updated_at) VALUES(1,?,true,now(),now())",
        id);
  }

  VulcanSessionMaterial material(String cookie) {
    return new VulcanSessionMaterial(base, base, "SUPER_SECRET_TOKEN", "SUPER_SECRET_GUID", cookie);
  }

  MonitoringSequenceReport execute(
      VulcanSessionManager manager, SequenceDispatchBudget budget, DelayStrategy delay) {
    var report = new MonitoringSequenceReport();
    var original = jdbc.queryForMap("SELECT * FROM vulcan_account_secret WHERE account_id=1");
    var account = jdbc.queryForMap("SELECT * FROM vulcan_account WHERE id=1");
    VulcanPersistedMonitoringSequence.execute(
        context,
        manager,
        budget,
        report,
        uri -> uri.equals(base.resolve("PlanLekcji.mvc/GetPlanLekcjiContext")),
        CLOCK,
        delay);
    Thread.interrupted();
    assertThat(jdbc.queryForMap("SELECT * FROM vulcan_account_secret WHERE account_id=1"))
        .usingRecursiveComparison()
        .isEqualTo(original);
    assertThat(jdbc.queryForMap("SELECT * FROM vulcan_account WHERE id=1"))
        .usingRecursiveComparison()
        .isEqualTo(account);
    for (String table : List.of("tracking_scope", "schedule_change_state", "notification_outbox"))
      assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).isZero();
    safe(report.json());
    return report;
  }

  MonitoringSequenceReport execute() {
    return execute(
        context.getBean(VulcanSessionManager.class), new SequenceDispatchBudget(), delays::add);
  }

  static void safe(String s) {
    assertThat(s)
        .doesNotContain(
            "SUPER_SECRET",
            "SECRET_TENANT",
            "SECRET_CLASS",
            "http://",
            "https://",
            "accountId",
            "journalId",
            "catalogId",
            "original=",
            "rotated=");
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> requests(MonitoringSequenceReport report) {
    return (List<Map<String, Object>>) report.facts().get("requests");
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
          assertThat(SessionFidelityDiagnostics.compare(expected, store.loadSession(1)).allSame())
              .isTrue();
        });
    var loaded = store.loadSession(1); // Separate transaction after flush/clear/commit.
    assertThat(loaded.cookieRepresentation())
        .isEqualTo(VulcanSessionMaterial.CookieRepresentation.STRUCTURED);
    assertThat(SessionFidelityDiagnostics.compare(expected, loaded).allSame()).isTrue();
    var reconstructed = manager.loadCurrent(1);
    assertThat(
            SessionFidelityDiagnostics.compare(expected, reconstructed.snapshotMaterial())
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

  @Test
  void duplicatePathCookieSurvivesPostSuccessPersistenceAndNextWithoutRetry() throws Exception {
    responseCookie = "original=SUPER_SECRET_COOKIE_B; Path=/";
    var report = execute();
    assertThat(report.facts())
        .containsEntry("result", "SUCCESS")
        .containsEntry("category", "SEQUENCE_COMPLETED")
        .containsEntry("current.sessionPersistedAfterSuccess", true)
        .containsEntry("current.persistence.applicationBaseSame", true)
        .containsEntry("current.persistence.refererSame", true)
        .containsEntry("current.persistence.verificationTokenSame", true)
        .containsEntry("current.persistence.appGuidSame", true)
        .containsEntry("current.persistence.cookieMaterialSame", true)
        .containsEntry("current.persistence.expectedCookieCount", 2)
        .containsEntry("current.persistence.actualCookieCount", 2)
        .containsEntry("current.persistence.cookieCountChanged", false)
        .containsEntry("current.liveCookieTopology.totalCookieCount", 2)
        .containsEntry("current.liveCookieTopology.duplicateNamePresent", true)
        .containsEntry("current.liveCookieTopology.duplicateNameDifferentPathPresent", true)
        .containsEntry("current.liveCookieTopology.duplicateNameDifferentDomainPresent", false)
        .containsEntry("current.materialRoundTrip.cookieMaterialSame", true)
        .containsEntry("current.materialRoundTrip.cookieCountBefore", 2)
        .containsEntry("current.materialRoundTrip.cookieCountAfter", 2)
        .containsEntry("current.outcome", "BASELINE_ESTABLISHED")
        .containsEntry("next.disposition", "DISPATCHED")
        .containsEntry("spacingAppliedBeforeNext", true)
        .containsEntry("next.loadedPostCurrentSession", true)
        .containsEntry("databaseSessionRestoredAfterRollback", true)
        .containsEntry("totalScheduleRequests", 2)
        .containsEntry("retries", 0);
    assertThat(requests(report)).hasSize(2);
    assertThat(requests(report).getFirst()).containsEntry("outcome", "SUCCESS");
    assertThat(calls.get()).isEqualTo(2);
    assertThat(delays).containsExactly(Duration.ofMillis(500));
    assertThat(cookies.getLast().contains("original=SUPER_SECRET_COOKIE_A")).isTrue();
    assertThat(cookies.getLast().contains("original=SUPER_SECRET_COOKIE_B")).isTrue();
    assertPowerShellReport(report);
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
          assertThat(SessionFidelityDiagnostics.compare(expected, actual).allSame()).isTrue();
          assertThat(
                  expected.cookiePairsForDiagnostics().equals(actual.cookiePairsForDiagnostics()))
              .isTrue();
        });
    // A separate transaction/repository read still preserves exact material.
    var actual = store.loadSession(1);
    assertThat(SessionFidelityDiagnostics.compare(expected, actual).allSame()).isTrue();
    assertThat(expected.cookiePairsForDiagnostics().equals(actual.cookiePairsForDiagnostics()))
        .isTrue();
    var reconstructed =
        context.getBean(VulcanSessionManager.class).loadCurrent(1).snapshotMaterial();
    assertThat(SessionFidelityDiagnostics.compare(expected, reconstructed).allSame())
        .isEqualTo(!duplicate);
    assertThat(calls.get()).isZero();
  }

  @Test
  void currentPersistsAndNextReloadsThroughRealEncryptedStoreInsideRollbackOnlyTransaction() {
    var manager = spy(context.getBean(VulcanSessionManager.class));
    var writes = new AtomicInteger();
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                  .isFalse();
              invocation.callRealMethod();
              writes.incrementAndGet();
              return null;
            })
        .when(manager)
        .replace(anyLong(), any());
    var report = execute(manager, new SequenceDispatchBudget(), delays::add);
    assertThat(report.facts())
        .containsEntry("result", "SUCCESS")
        .containsEntry("scopeCount", 2)
        .containsEntry("current.sessionPersistedAfterSuccess", true)
        .containsEntry("next.loadedPostCurrentSession", true)
        .containsEntry("databaseSessionRestoredAfterRollback", true)
        .containsEntry("spacingAppliedBeforeNext", true)
        .containsEntry("gateInitiallyClear", true)
        .containsEntry("accountBlockedAfterCurrent", false)
        .containsEntry("totalScheduleRequests", 2)
        .containsEntry("retries", 0);
    for (String key : MonitoringSequenceReport.FIDELITY_BOOLEANS) {
      boolean expected = !key.contains("duplicateName") && !key.endsWith("cookieCountChanged");
      assertThat(report.facts()).containsEntry(key, expected);
    }
    for (String key : MonitoringSequenceReport.FIDELITY_COUNTS)
      assertThat(report.facts()).containsEntry(key, 2);
    assertThat(requests(report)).extracting(r -> r.get("scope")).containsExactly("CURRENT", "NEXT");
    assertThat(requests(report).getFirst())
        .containsEntry("cookieCountBefore", 1)
        .containsEntry("cookieCountAfter", 2)
        .containsEntry("cookieMaterialChanged", true);
    assertThat(requests(report).getLast())
        .containsEntry("cookieCountBefore", 2)
        .containsEntry("cookieMaterialChanged", false);
    assertThat(cookies.getFirst()).doesNotContain("SUPER_SECRET_COOKIE_B");
    assertThat(cookies.getLast()).contains("SUPER_SECRET_COOKIE_B");
    assertThat(forms.getFirst())
        .containsEntry("dataOd", "2026-09-07T00:00:00")
        .containsEntry("dataDo", "2026-09-13T00:00:00");
    assertThat(forms.getLast())
        .containsEntry("dataOd", "2026-09-14T00:00:00")
        .containsEntry("dataDo", "2026-09-20T00:00:00");
    assertThat(delays).containsExactly(Duration.ofMillis(500));
    assertThat(calls.get()).isEqualTo(2);
    assertThat(writes.get()).isEqualTo(2);
    verify(manager, never()).recover(anyLong());
    verify(manager, never()).markReconnectRequired(anyLong());
  }

  @Test
  void current429WithoutRetryAfterExtendsGateAndSkipsNextWithOneRequest() {
    statuses = new int[] {429};
    var report = execute();
    assertThat(report.facts())
        .containsEntry("current.outcome", "DEFERRED_RATE_LIMIT")
        .containsEntry("accountBlockedAfterCurrent", true)
        .containsEntry("gateInitiallyClear", true)
        .containsEntry("next.disposition", "SKIPPED_ACCOUNT_BLOCKED")
        .containsEntry("totalScheduleRequests", 1)
        .containsEntry("current.sessionPersistedAfterSuccess", "UNAVAILABLE")
        .containsEntry("databaseSessionRestoredAfterRollback", true);
    assertThat(requests(report).getFirst())
        .containsEntry("status429", true)
        .containsEntry("cookieMaterialChanged", true)
        .containsEntry("retryAfterPresent", false);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(delays).isEmpty();
  }

  @Test
  void next429SeesPersistedCurrentAndRollbackStillRestoresOriginal() {
    statuses = new int[] {200, 429};
    var report = execute();
    assertThat(report.facts())
        .containsEntry("current.sessionPersistedAfterSuccess", true)
        .containsEntry("next.loadedPostCurrentSession", true)
        .containsEntry("next.outcome", "DEFERRED_RATE_LIMIT")
        .containsEntry("next.disposition", "DISPATCHED")
        .containsEntry("databaseSessionRestoredAfterRollback", true)
        .containsEntry("totalScheduleRequests", 2);
    assertThat(requests(report).getLast()).containsEntry("status429", true);
    assertThat(delays).containsExactly(Duration.ofMillis(500));
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 503, 429})
  void resilienceCannotSendThirdRequestEvenWhenItsPolicyWantsRetry(int status) {
    statuses = new int[] {status};
    if (status == 429) retryAfter = "0";
    var report = execute();
    assertThat(report.facts())
        .containsEntry("category", "BUDGET_EXHAUSTED")
        .containsEntry("totalScheduleRequests", 2)
        .containsEntry("retries", 1)
        .containsEntry("next.disposition", "SKIPPED_BUDGET")
        .containsEntry("databaseSessionRestoredAfterRollback", true);
    assertThat(requests(report)).hasSize(2).allMatch(r -> r.get("scope").equals("CURRENT"));
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void currentRetrySuccessCannotLeaveBudgetForNext() {
    statuses = new int[] {503, 200};
    var report = execute();
    assertThat(report.facts())
        .containsEntry("category", "BUDGET_EXHAUSTED")
        .containsEntry("current.sessionPersistedAfterSuccess", true)
        .containsEntry("next.disposition", "SKIPPED_BUDGET")
        .containsEntry("totalScheduleRequests", 2);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void sessionPersistenceFailureStopsAndRollsBackAlreadyFlushedCurrentUpdate() {
    var manager = spy(context.getBean(VulcanSessionManager.class));
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              context.getBean(jakarta.persistence.EntityManager.class).flush();
              throw new IllegalStateException("SUPER_SECRET_COOKIE_A");
            })
        .when(manager)
        .replace(anyLong(), any());
    var report = execute(manager, new SequenceDispatchBudget(), delays::add);
    assertThat(report.facts())
        .containsEntry("category", "HARNESS_FAILURE")
        .containsEntry("databaseSessionRestoredAfterRollback", true)
        .containsEntry("next.disposition", "SKIPPED_INTERRUPTED");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void nextTransientRetryCannotExceedTwoRequestBudget() {
    statuses = new int[] {200, 503};
    var report = execute();
    assertThat(report.facts())
        .containsEntry("category", "BUDGET_EXHAUSTED")
        .containsEntry("totalScheduleRequests", 2)
        .containsEntry("next.disposition", "DISPATCHED")
        .containsEntry("next.outcome", "BUDGET_EXHAUSTED");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void semanticMaterialComparisonIgnoresOnlyCookieOrdering() {
    assertThat(
            VulcanPersistedMonitoringSequence.sameMaterial(
                material("a=A; b=B"), material("b=B; a=A")))
        .isTrue();
    assertThat(
            VulcanPersistedMonitoringSequence.sameMaterial(
                material("a=A; b=B"), material("a=C; b=B")))
        .isFalse();
    assertThat(
            VulcanPersistedMonitoringSequence.sameMaterial(
                material("a=A; b=B"), material("a=A; b=B; b=B")))
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 302, 401, 403})
  void authenticationAndHtmlStopWithoutRecovery(int status) {
    statuses = new int[] {status};
    html = status == 200;
    var manager = spy(context.getBean(VulcanSessionManager.class));
    var report = execute(manager, new SequenceDispatchBudget(), delays::add);
    assertThat(report.facts())
        .containsEntry("current.outcome", "AUTHENTICATION_REQUIRED")
        .containsEntry("next.disposition", "SKIPPED_ACCOUNT_BLOCKED")
        .containsEntry("totalScheduleRequests", 1);
    verify(manager, never()).recover(anyLong());
    verify(manager, never()).replace(anyLong(), any());
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"MULTIPLE", "ZERO", "DISCONNECTED", "OWNER", "INACTIVE"})
  void strictTargetPreflightCannotDispatch(String state) {
    switch (state) {
      case "MULTIPLE" -> target(2);
      case "ZERO" -> jdbc.update("DELETE FROM monitoring_subscription");
      case "DISCONNECTED" -> jdbc.update("UPDATE vulcan_account SET status='DISCONNECTED'");
      case "OWNER" -> {
        jdbc.update("INSERT INTO app_user(id,created_at,updated_at) VALUES(2,now(),now())");
        jdbc.update("UPDATE monitoring_subscription SET app_user_id=2");
      }
      case "INACTIVE" -> jdbc.update("UPDATE vulcan_class_catalog SET active=false");
    }
    var report = execute();
    assertThat(report.facts())
        .containsEntry("totalScheduleRequests", 0)
        .containsEntry("targetResolved", false);
    assertThat(calls.get()).isZero();
  }

  @Test
  void interruptedSpacingRollsBackCurrentAndSkipsNext() {
    var report =
        execute(
            context.getBean(VulcanSessionManager.class),
            new SequenceDispatchBudget(),
            d -> {
              throw new InterruptedException();
            });
    assertThat(report.facts())
        .containsEntry("next.disposition", "SKIPPED_INTERRUPTED")
        .containsEntry("spacingAppliedBeforeNext", false)
        .containsEntry("databaseSessionRestoredAfterRollback", true);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void dispatchGuardCannotBeReusedForThirdRequestOrOtherEndpoint() {
    var budget = new SequenceDispatchBudget();
    var client =
        new VulcanClient(VulcanSession.fromMaterial(material("original=SUPER_SECRET_COOKIE_A")));
    budget.install(client, base.resolve("PlanLekcji.mvc/GetPlanLekcjiContext"));
    client.getWeekSchedule(101, LocalDate.of(2026, 9, 7));
    client.getWeekSchedule(101, LocalDate.of(2026, 9, 14));
    assertThatThrownBy(() -> client.getWeekSchedule(101, LocalDate.of(2026, 9, 7)))
        .isInstanceOf(SequenceDispatchBudget.Exhausted.class);
    Object adapter =
        org.springframework.test.util.ReflectionTestUtils.getField(client, "scheduleAdapter");
    Object transport =
        org.springframework.test.util.ReflectionTestUtils.getField(adapter, "transport");
    Object rest =
        org.springframework.test.util.ReflectionTestUtils.getField(transport, "restClient");
    var factory =
        (org.springframework.http.client.ClientHttpRequestFactory)
            org.springframework.test.util.ReflectionTestUtils.getField(
                rest, "clientRequestFactory");
    assertThatThrownBy(
            () ->
                factory.createRequest(
                    base.resolve("Other.mvc"), org.springframework.http.HttpMethod.GET))
        .hasMessage("UNSAFE_REQUEST");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void secretExceptionsAndRequestObservationsNeverLeakToStreams() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var oldOut = System.out;
    var oldErr = System.err;
    try (var stdout = new PrintStream(out);
        var stderr = new PrintStream(err)) {
      System.setOut(stdout);
      System.setErr(stderr);
      var manager = spy(context.getBean(VulcanSessionManager.class));
      doThrow(new IllegalStateException("SUPER_SECRET_TOKEN")).when(manager).loadCurrent(anyLong());
      var report = execute(manager, new SequenceDispatchBudget(), delays::add);
      System.out.println(report.json());
      assertThatThrownBy(() -> report.put("cookieValues", "SUPER_SECRET_COOKIE_A"))
          .hasMessage("UNSAFE_OUTPUT_GUARD");
    } finally {
      System.setOut(oldOut);
      System.setErr(oldErr);
    }
    safe(out.toString(StandardCharsets.UTF_8));
    safe(err.toString(StandardCharsets.UTF_8));
    assertThat(err.size()).isZero();
  }

  @Test
  void noOptInStartsNoSpringOrProvider() throws Exception {
    var result =
        NativeSessionBaselineTest.run(
            new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                VulcanPersistedMonitoringSequence.class.getName()),
            new byte[0]);
    assertThat(result.exit()).isEqualTo(1);
    assertThat(result.out()).contains("NOT_AUTHORIZED");
    safe(result.out());
    assertThat(calls.get()).isZero();
  }

  @Test
  void powershellContracts() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    var result =
        NativeSessionBaselineTest.run(
            new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/tests/vulcan-persisted-monitoring-sequence.Tests.ps1"),
            new byte[0]);
    assertThat(result.exit()).isZero();
    assertThat(result.out()).contains("Sequence PowerShell contracts passed");
  }

  @Test
  void actualJavaSuccessReportPassesPowerShellOutputGuard() throws Exception {
    assertPowerShellReport(execute());
  }

  private void assertPowerShellReport(MonitoringSequenceReport report) throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    var result =
        NativeSessionBaselineTest.run(
            new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/tests/vulcan-persisted-monitoring-sequence.Tests.ps1",
                "-ReportStdin"),
            report.json().getBytes(StandardCharsets.UTF_8));
    assertThat(result.exit()).isZero();
    assertThat(result.out()).contains("Sequence report validation passed");
  }
}
