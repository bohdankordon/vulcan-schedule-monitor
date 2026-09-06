package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.PersistedBaselineConfiguration;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One explicit read-only local-dev observation. No HAR, authentication, discovery, or retry. */
public final class VulcanPersistedSessionJavaBaseline {
  public static void main(String[] args) {
    PrintStream output = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    var report = new PersistedBaselineReport();
    try {
      if (args.length == 1 && args[0].equals("--authorized-persisted-session-baseline")) {
        report.put("category", "APP_RUNNING");
        // Reserve the normal dev application's port for the entire diagnostic; never stop it.
        try (ServerSocket guard = reserveApplicationPort(8080)) {
          report.put("category", "KEY_UNAVAILABLE");
          byte[] bytes = System.in.readNBytes(129);
          String key;
          try {
            if (bytes.length != 44) throw new IllegalArgumentException();
            key = new String(bytes, StandardCharsets.US_ASCII);
            byte[] decoded = Base64.getDecoder().decode(key);
            try {
              if (decoded.length != 32) throw new IllegalArgumentException();
            } finally {
              Arrays.fill(decoded, (byte) 0);
            }
          } finally {
            Arrays.fill(bytes, (byte) 0);
          }
          report.put("category", "DATABASE_UNAVAILABLE");
          try (var context =
              open(
                  "jdbc:postgresql://localhost:54329/vulcan_monitor",
                  "vulcan",
                  "vulcan-local-dev-only",
                  key,
                  true)) {
            execute(
                context,
                context.getBean(VulcanSessionManager.class),
                new VulcanNativeSessionJavaBaseline.Permit(),
                report,
                new PortalUrlValidator()::isAllowedRuntimeUri,
                context.getBean(Clock.class));
          }
        }
      }
    } catch (Throwable ignored) {
      /* Only the current finite boundary survives. */
    }
    output.println(report.json());
    System.exit("SUCCESS".equals(report.facts().get("result")) ? 0 : 1);
  }

  static ServerSocket reserveApplicationPort(int port) throws IOException {
    var socket = new ServerSocket();
    try {
      socket.setReuseAddress(false);
      socket.bind(new InetSocketAddress(port));
      return socket;
    } catch (IOException failure) {
      socket.close();
      throw failure;
    }
  }

  /**
   * Tests supply only a Testcontainers JDBC destination. Real CLI has no configurable destination.
   */
  static ConfigurableApplicationContext open(
      String jdbc, String user, String password, String key, boolean readOnly) {
    Map<String, Object> p = new HashMap<>();
    p.put("spring.datasource.url", jdbc);
    p.put("spring.datasource.username", user);
    p.put("spring.datasource.password", password);
    p.put("vulcan.connection.master-key", key);
    p.put("vulcan.connection.enabled", "true");
    p.put("vulcan.monitoring.enabled", "false");
    p.put("telegram.bot.enabled", "false");
    p.put("spring.flyway.enabled", "false");
    p.put("spring.sql.init.mode", "never");
    p.put("spring.jpa.hibernate.ddl-auto", "validate");
    p.put("spring.jpa.open-in-view", "false");
    p.put("spring.jpa.show-sql", "false");
    p.put("spring.datasource.hikari.maximum-pool-size", "2");
    p.put("spring.datasource.hikari.connection-timeout", "5000");
    p.put("spring.datasource.hikari.read-only", Boolean.toString(readOnly));
    if (readOnly)
      p.put(
          "spring.datasource.hikari.connection-init-sql", "SET default_transaction_read_only = on");
    p.put("spring.config.location", "optional:classpath:/persisted-baseline-no-config.properties");
    p.put("spring.main.banner-mode", "off");
    p.put("logging.level.root", "OFF");
    var environment = new StandardEnvironment();
    for (var source : environment.getPropertySources())
      environment.getPropertySources().remove(source.getName());
    environment.getPropertySources().addFirst(new MapPropertySource("diagnostic", p));
    var app = new SpringApplication(PersistedBaselineConfiguration.class);
    app.setEnvironment(environment);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setAddCommandLineProperties(false);
    app.setLogStartupInfo(false);
    app.setRegisterShutdownHook(false);
    return app.run();
  }

  private record Account(long id, String status) {}

  private record Loaded(
      long journal,
      io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession session) {}

  static void execute(
      ConfigurableApplicationContext context,
      VulcanSessionManager sessions,
      VulcanNativeSessionJavaBaseline.Permit permit,
      PersistedBaselineReport report,
      Predicate<URI> allowed,
      Clock clock) {
    try {
      var jdbc = context.getBean(JdbcTemplate.class);
      var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      tx.setReadOnly(true);
      tx.setIsolationLevel(
          org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
      report.put("category", "DATABASE_UNAVAILABLE");
      Loaded loaded =
          tx.execute(
              status -> {
                var accounts =
                    jdbc.query(
                        "SELECT a.id,a.status FROM vulcan_account a JOIN app_user u ON u.id=a.app_user_id WHERE u.active ORDER BY a.id LIMIT 2",
                        (rs, row) -> new Account(rs.getLong(1), rs.getString(2)));
                if (accounts.size() != 1) {
                  report.put("category", accounts.isEmpty() ? "NO_ACCOUNT" : "AMBIGUOUS_ACCOUNT");
                  return null;
                }
                var account = accounts.getFirst();
                report.put("account.connected", account.status().equals("CONNECTED"));
                report.put(
                    "account.reconnectRequired", account.status().equals("RECONNECT_REQUIRED"));
                if (!account.status().equals("CONNECTED")) {
                  report.put("category", "ACCOUNT_NOT_CONNECTED");
                  return null;
                }
                var journals =
                    jdbc.query(
                        """
            SELECT c.journal_id FROM monitoring_subscription s
            JOIN app_user u ON u.id=s.app_user_id
            JOIN vulcan_class_catalog c ON c.id=s.catalog_class_id
            JOIN vulcan_account a ON a.id=c.vulcan_account_id AND a.app_user_id=s.app_user_id
            WHERE s.enabled AND u.active AND c.active AND a.status='CONNECTED' AND a.id=?
            ORDER BY c.id LIMIT 2
            """,
                        (rs, row) -> rs.getLong(1),
                        account.id());
                if (journals.size() != 1) {
                  report.put("category", journals.isEmpty() ? "NO_TARGET" : "AMBIGUOUS_TARGET");
                  return null;
                }
                report.put("targetResolved", true);
                report.put("category", "SESSION_UNAVAILABLE");
                var session = sessions.loadCurrent(account.id());
                report.put("persistedSessionLoaded", true);
                return new Loaded(journals.getFirst(), session);
              });
      if (loaded == null) return;
      report.put("category", "UNSAFE_SESSION");
      if (!allowed.test(loaded.session().resolve("PlanLekcji.mvc/GetPlanLekcjiContext"))) return;
      var monday =
          LocalDate.now(clock.withZone(ZoneId.of("Europe/Warsaw")))
              .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      report.put("form.weekStartValid", monday.getDayOfWeek() == DayOfWeek.MONDAY);
      report.put("form.weekEndValid", monday.plusDays(6).getDayOfWeek() == DayOfWeek.SUNDAY);
      report.put("category", "HARNESS_FAILURE");
      var client = new VulcanClient(loaded.session(), clock);
      VulcanNativeSessionJavaBaseline.runOnce(
          permit,
          report.observation,
          () ->
              NativeSessionCookieObservation.observe(
                  loaded.session()::snapshotMaterial,
                  report.observation,
                  () -> client.getWeekSchedule(loaded.journal(), monday)));
      report.put("category", report.observation.facts().get("category"));
      // Deliberately no replace/recovery/save: discard the in-memory session even after success.
    } catch (Throwable ignored) {
      /* Never propagate database/session/provider exception text. */
    }
  }
}
