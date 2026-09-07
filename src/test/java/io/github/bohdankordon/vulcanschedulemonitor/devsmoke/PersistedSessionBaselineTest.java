package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistedSessionBaselineTest {
  private final PostgreSQLContainer database = new PostgreSQLContainer("postgres:18.6");
  private ConfigurableApplicationContext writer, reader;
  private JdbcTemplate jdbc;
  private HttpServer server;
  private URI base;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> body = new AtomicReference<>();
  private final AtomicReference<Map<String, List<String>>> headers = new AtomicReference<>();
  private int responseStatus;
  private String responseFamily;
  private boolean rotate;
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  // Sunday UTC, but already Monday in Warsaw: catches use of the wrong timezone/week.
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-06T22:30:00Z"), ZoneOffset.UTC);

  @BeforeAll
  void contexts() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try (exchange) {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.set(new TreeMap<>(exchange.getRequestHeaders()));
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestURI().getPath())
                .isEqualTo("/SECRET_TENANT/PlanLekcji.mvc/GetPlanLekcjiContext");
            exchange.getResponseHeaders().set("Content-Type", responseFamily);
            if (rotate)
              exchange
                  .getResponseHeaders()
                  .add("Set-Cookie", "added=SUPER_SECRET_COOKIE_B; Path=/SECRET_TENANT/");
            if (responseStatus == 429) exchange.getResponseHeaders().set("Retry-After", "7");
            if (responseStatus == 302)
              exchange.getResponseHeaders().set("Location", "/never-follow");
            byte[] bytes =
                (responseFamily.equals("text/html")
                        ? "<html>SUPER_SECRET_COOKIE_B</html>"
                        : "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_TENANT/");
    database.start();
    Flyway.configure()
        .dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
        .load()
        .migrate();
    writer =
        VulcanPersistedSessionJavaBaseline.open(
            database.getJdbcUrl(), database.getUsername(), database.getPassword(), KEY, false);
    reader =
        VulcanPersistedSessionJavaBaseline.open(
            database.getJdbcUrl(), database.getUsername(), database.getPassword(), KEY, true);
    jdbc = writer.getBean(JdbcTemplate.class);
    assertThat(calls.get()).isZero();
  }

  @AfterAll
  void close() {
    if (reader != null) reader.close();
    if (writer != null) writer.close();
    if (server != null) server.stop(0);
    database.stop();
  }

  @BeforeEach
  void fixture() {
    jdbc.execute("TRUNCATE app_user CASCADE");
    jdbc.update("INSERT INTO app_user(id,created_at,updated_at) VALUES(1,now(),now())");
    jdbc.update(
        "INSERT INTO vulcan_account(id,app_user_id,status,created_at,updated_at) VALUES(1,1,'CONNECTED',now(),now())");
    catalog(1, 1);
    writer
        .getBean(VulcanSecretStore.class)
        .replace(
            1,
            new VulcanSessionMaterial(
                base,
                base.resolve("Other.mvc"),
                "SUPER_SECRET_TOKEN",
                "SUPER_SECRET_GUID",
                "one=SUPER_SECRET_COOKIE_A"),
            null,
            CLOCK.instant());
    calls.set(0);
    body.set(null);
    headers.set(null);
    responseStatus = 200;
    responseFamily = "application/json";
    rotate = false;
  }

  private void catalog(long id, long owner) {
    jdbc.update(
        "INSERT INTO vulcan_class_catalog(id,vulcan_account_id,journal_id,class_id,name,school_year,synced_at) VALUES(?,1,?,?,'CLASS_SECRET',2026,now())",
        id,
        100 + id,
        id);
    jdbc.update(
        "INSERT INTO monitoring_subscription(app_user_id,catalog_class_id,enabled,created_at,updated_at) VALUES(?,?,true,now(),now())",
        owner,
        id);
  }

  private PersistedBaselineReport execute(
      VulcanNativeSessionJavaBaseline.Permit permit, VulcanSessionManager manager) {
    var report = new PersistedBaselineReport();
    VulcanPersistedSessionJavaBaseline.execute(
        reader,
        manager,
        permit,
        report,
        uri -> uri.equals(base.resolve("PlanLekcji.mvc/GetPlanLekcjiContext")),
        CLOCK);
    safe(report.json());
    return report;
  }

  private PersistedBaselineReport execute() {
    return execute(
        new VulcanNativeSessionJavaBaseline.Permit(), reader.getBean(VulcanSessionManager.class));
  }

  private static void safe(String value) {
    assertThat(value)
        .doesNotContain(
            "SUPER_SECRET",
            "SECRET_TENANT",
            "CLASS_SECRET",
            "http://",
            "https://",
            "one=",
            "added=",
            "journal_id",
            "account_id");
  }

  @ParameterizedTest
  @CsvSource({
    "200,application/json,false,SUCCESS",
    "200,application/json,true,SUCCESS",
    "429,application/json,false,RATE_LIMITED",
    "429,application/json,true,RATE_LIMITED",
    "200,text/html,true,UNEXPECTED_HTML",
    "302,text/html,true,SESSION_REDIRECT",
    "401,application/json,true,AUTHENTICATION_REQUIRED",
    "503,application/json,true,SERVER_ERROR"
  })
  void realEncryptedLoadAndUntouchedJdkRequestNeverSaveEvenOnFailure(
      int status, String family, boolean mutation, String outcome) {
    responseStatus = status;
    responseFamily = family;
    rotate = mutation;
    byte[] encrypted =
        jdbc.queryForObject(
            "SELECT session_ciphertext FROM vulcan_account_secret WHERE account_id=1",
            byte[].class);
    var before = jdbc.queryForMap("SELECT * FROM vulcan_account_secret WHERE account_id=1");
    var manager = spy(reader.getBean(VulcanSessionManager.class));
    var permit = new VulcanNativeSessionJavaBaseline.Permit();
    var report = execute(permit, manager);
    assertThat(report.facts())
        .containsEntry("persistedSessionLoaded", true)
        .containsEntry("targetResolved", true)
        .containsEntry("javaOutcome", outcome)
        .containsEntry("session.cookieCountBefore", 1)
        .containsEntry("session.cookieCountAfter", mutation ? 2 : 1)
        .containsEntry("session.cookieCountChanged", mutation)
        .containsEntry("session.cookieMaterialChanged", mutation)
        .containsEntry("javaScheduleRequests", 1)
        .containsEntry("retries", 0)
        .containsEntry("form.weekStartValid", true)
        .containsEntry("form.weekEndValid", true);
    if (status == 429)
      assertThat(report.facts())
          .containsEntry("retryAfterPresent", true)
          .containsEntry("retryAfterSeconds", 7L);
    verify(manager).loadCurrent(1);
    verify(manager, never()).replace(anyLong(), any());
    verify(manager, never()).recover(anyLong());
    verify(manager, never()).markReconnectRequired(anyLong());
    assertThat(
            jdbc.queryForObject(
                "SELECT session_ciphertext FROM vulcan_account_secret WHERE account_id=1",
                byte[].class))
        .isEqualTo(encrypted);
    assertThat(jdbc.queryForMap("SELECT * FROM vulcan_account_secret WHERE account_id=1"))
        .usingRecursiveComparison()
        .isEqualTo(before);
    assertThat(
            reader
                .getBean(VulcanSessionManager.class)
                .loadCurrent(1)
                .snapshotMaterial()
                .cookiePairsForDiagnostics())
        .doesNotContain("COOKIE_B");
    assertThat(Schedule429Structure.formValues(body.get()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "dataOd",
                "2026-09-07T00:00:00",
                "dataDo",
                "2026-09-13T00:00:00",
                "data",
                "2026-09-07T00:00:00",
                "idDziennik",
                "101"));
    assertThat(headers.get().keySet())
        .noneMatch(
            name ->
                Set.of("accept", "accept-language", "sec-fetch-site")
                    .contains(name.toLowerCase(Locale.ROOT)));
    assertThat(execute(permit, manager).facts())
        .containsEntry("category", "BUDGET_EXHAUSTED")
        .containsEntry("javaScheduleRequests", 0);
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({
    "NO_ACCOUNT,NO_ACCOUNT",
    "DISCONNECTED,ACCOUNT_NOT_CONNECTED",
    "RECONNECT_REQUIRED,ACCOUNT_NOT_CONNECTED",
    "ZERO,NO_TARGET",
    "MULTIPLE,AMBIGUOUS_TARGET",
    "FOREIGN_OWNER,NO_TARGET",
    "INACTIVE,NO_TARGET",
    "DISABLED,NO_TARGET",
    "MULTIPLE_ACCOUNTS,AMBIGUOUS_ACCOUNT",
    "NO_SECRET,SESSION_UNAVAILABLE"
  })
  void targetAndSessionPreflightFailsWithoutTraffic(String scenario, String category) {
    switch (scenario) {
      case "NO_ACCOUNT" -> jdbc.execute("TRUNCATE app_user CASCADE");
      case "DISCONNECTED", "RECONNECT_REQUIRED" ->
          jdbc.update("UPDATE vulcan_account SET status=?", scenario);
      case "ZERO" -> jdbc.execute("DELETE FROM monitoring_subscription");
      case "MULTIPLE" -> catalog(2, 1);
      case "FOREIGN_OWNER" -> {
        jdbc.update("INSERT INTO app_user(id,created_at,updated_at) VALUES(2,now(),now())");
        jdbc.update("UPDATE monitoring_subscription SET app_user_id=2");
      }
      case "INACTIVE" -> jdbc.update("UPDATE vulcan_class_catalog SET active=false");
      case "DISABLED" -> jdbc.update("UPDATE monitoring_subscription SET enabled=false");
      case "MULTIPLE_ACCOUNTS" -> {
        jdbc.update("INSERT INTO app_user(id,created_at,updated_at) VALUES(2,now(),now())");
        jdbc.update(
            "INSERT INTO vulcan_account(id,app_user_id,status,created_at,updated_at) VALUES(2,2,'CONNECTED',now(),now())");
      }
      case "NO_SECRET" -> jdbc.execute("DELETE FROM vulcan_account_secret");
      default -> throw new AssertionError();
    }
    var report = execute();
    assertThat(report.facts())
        .containsEntry("category", category)
        .containsEntry("javaRequestAttempted", false)
        .containsEntry("session.cookieCountAfter", "UNAVAILABLE");
    assertThat(calls.get()).isZero();
  }

  @Test
  void runtimeContextHasNoSchedulerTelegramBrowserOrStartupProviderCallsAndDatabaseRejectsWrites() {
    for (String name : reader.getBeanDefinitionNames()) {
      Class<?> type = reader.getType(name);
      if (type != null)
        assertThat(type.getName())
            .doesNotContain(
                "MonitoringCycleRunner",
                "ScheduleRefreshCoordinator",
                "ResilientWeeklyScheduleSource",
                "RecoveringAccountWeeklyScheduleSource",
                "RateLimitBackoffGate",
                "Telegram",
                "Playwright",
                "ScheduledAnnotationBeanPostProcessor",
                "FlywayMigrationInitializer");
    }
    assertThat(calls.get()).isZero();
    assertThatThrownBy(
            () -> reader.getBean(JdbcTemplate.class).update("UPDATE app_user SET active=false"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(jdbc.queryForObject("SELECT active FROM app_user WHERE id=1", Boolean.class))
        .isTrue();
  }

  @Test
  void unsafeDestinationNeverDispatches() {
    var report = new PersistedBaselineReport();
    VulcanPersistedSessionJavaBaseline.execute(
        reader,
        reader.getBean(VulcanSessionManager.class),
        new VulcanNativeSessionJavaBaseline.Permit(),
        report,
        new PortalUrlValidator()::isAllowedRuntimeUri,
        CLOCK);
    assertThat(report.facts())
        .containsEntry("category", "UNSAFE_SESSION")
        .containsEntry("javaScheduleRequests", 0);
    assertThat(calls.get()).isZero();
  }

  @Test
  void refusedLoopbackTransportLeavesPostCookieStateUnavailable() throws Exception {
    URI closed;
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      closed = URI.create("http://127.0.0.1:" + socket.getLocalPort() + "/SECRET_TENANT/");
    }
    writer
        .getBean(VulcanSecretStore.class)
        .replace(
            1,
            new VulcanSessionMaterial(
                closed,
                closed,
                "SUPER_SECRET_TOKEN",
                "SUPER_SECRET_GUID",
                "one=SUPER_SECRET_COOKIE_A"),
            null,
            CLOCK.instant());
    var report = new PersistedBaselineReport();
    VulcanPersistedSessionJavaBaseline.execute(
        reader,
        reader.getBean(VulcanSessionManager.class),
        new VulcanNativeSessionJavaBaseline.Permit(),
        report,
        uri -> uri.equals(closed.resolve("PlanLekcji.mvc/GetPlanLekcjiContext")),
        CLOCK);
    assertThat(report.facts())
        .containsEntry("javaOutcome", "TRANSPORT_ERROR")
        .containsEntry("session.cookieCountAfter", "UNAVAILABLE")
        .containsEntry("session.cookieMaterialChanged", "UNAVAILABLE")
        .containsEntry("retries", 0);
    safe(report.json());
    assertThat(calls.get()).isZero();
  }

  @Test
  void environmentCannotEnableSchedulersOrChangeDestination() {
    assertThat(reader.getEnvironment().getProperty("vulcan.monitoring.enabled")).isEqualTo("false");
    assertThat(reader.getEnvironment().getProperty("telegram.bot.enabled")).isEqualTo("false");
    assertThat(reader.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
    assertThat(reader.getEnvironment().getProperty("spring.datasource.url"))
        .isEqualTo(database.getJdbcUrl());
    assertThat(reader.getEnvironment().getPropertySources().contains("systemEnvironment"))
        .isFalse();
    assertThat(reader.getEnvironment().getPropertySources().contains("systemProperties")).isFalse();
  }

  @Test
  void driverRequiresOptInWithoutReadingStdinOrStartingContext() throws Exception {
    String cp =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var result =
        NativeSessionBaselineTest.run(
            new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                cp,
                VulcanPersistedSessionJavaBaseline.class.getName()),
            new byte[0]);
    assertThat(result.exit()).isEqualTo(1);
    assertThat(result.out()).contains("NOT_AUTHORIZED", "PERSISTED_CONNECT_SESSION");
    safe(result.out());
    assertThat(calls.get()).isZero();
  }

  @Test
  void schemaRejectsArbitraryDetailAndSecretsNeverReachStreams() {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    PrintStream oldOut = System.out, oldErr = System.err;
    try (var stdout = new PrintStream(out);
        var stderr = new PrintStream(err)) {
      System.setOut(stdout);
      System.setErr(stderr);
      var manager = spy(reader.getBean(VulcanSessionManager.class));
      doThrow(new IllegalArgumentException("SUPER_SECRET_TOKEN SUPER_SECRET_COOKIE_A"))
          .when(manager)
          .loadCurrent(anyLong());
      var report = execute(new VulcanNativeSessionJavaBaseline.Permit(), manager);
      System.out.println(report.json());
      assertThat(report.facts()).containsEntry("category", "SESSION_UNAVAILABLE");
      assertThatThrownBy(() -> report.put("cookieNames", "SUPER_SECRET_COOKIE_A"))
          .hasMessage("UNSAFE_OUTPUT_GUARD");
    } finally {
      System.setOut(oldOut);
      System.setErr(oldErr);
    }
    safe(out.toString(StandardCharsets.UTF_8));
    safe(err.toString(StandardCharsets.UTF_8));
    assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    assertThat(calls.get()).isZero();
  }

  @Test
  void appPortGuardIsExclusiveAndDoesNotStopOwner() throws Exception {
    try (var socket = VulcanPersistedSessionJavaBaseline.reserveApplicationPort(0)) {
      assertThatThrownBy(
              () ->
                  VulcanPersistedSessionJavaBaseline.reserveApplicationPort(socket.getLocalPort()))
          .isInstanceOf(IOException.class);
      assertThat(socket.isClosed()).isFalse();
    }
  }

  @Test
  void powershellSyntheticContracts() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getProperty("os.name").startsWith("Windows"));
    var result =
        NativeSessionBaselineTest.run(
            new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/tests/vulcan-persisted-session-java-baseline.Tests.ps1"),
            new byte[0]);
    assertThat(result.exit()).isZero();
    assertThat(result.out()).contains("Persisted baseline PowerShell contracts passed");
  }
}
