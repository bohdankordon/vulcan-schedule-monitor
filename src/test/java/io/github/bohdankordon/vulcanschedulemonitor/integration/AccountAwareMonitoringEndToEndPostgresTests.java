package io.github.bohdankordon.vulcanschedulemonitor.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringScopePlanner;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTarget;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTargetProvider;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.RateLimitBackoffGate;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.RecoveringAccountWeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.ResilientWeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.ScheduleSourceException;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.SourceFailureKind;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleChangeTracker;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleRefreshCoordinator;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxStore;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationDeliveryGateway;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationFormatter;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.users.ApplicationUser;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientDirectory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.RememberedCredentials;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VerifiedVulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanBrowserAuthenticator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionManager;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionVerifier;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.VulcanClassCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule.PersistedAccountWeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "vulcan.connection.enabled=true",
      "vulcan.connection.public-base-url=http://localhost:8080/",
      "vulcan.connection.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@Import(AccountAwareMonitoringEndToEndPostgresTests.Fakes.class)
class AccountAwareMonitoringEndToEndPostgresTests extends PostgresIntegrationTestSupport {

  private static final Instant NOW = Instant.parse("2099-09-09T10:15:30Z");
  private static final LocalDate WEEK_START = LocalDate.of(2099, 9, 7);
  private static final String SCHEDULE_ENDPOINT = "PlanLekcji.mvc/GetPlanLekcjiContext";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TelegramIdentityRegistration identities;
  @Autowired private VulcanSecretStore secretStore;
  @Autowired private VulcanSessionManager sessions;
  @Autowired private MonitoringSubscriptionService subscriptions;
  @Autowired private MonitoringTargetProvider targetProvider;
  @Autowired private ScheduleChangeTracker tracker;
  @Autowired private NotificationOutboxStore outboxStore;
  @Autowired private TelegramRecipientDirectory recipients;
  @Autowired private VulcanClassCatalog catalog;
  @Autowired private Clock clock;
  @Autowired private Fakes fakes;

  private final RecordingTelegramTransport telegram = new RecordingTelegramTransport();
  private WireMockServer server;

  @BeforeEach
  void reset() {
    clearDatabase();
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    telegram.sent.clear();
    fakes.authenticationCalls.set(0);
    fakes.recoveredMaterial.set(null);
  }

  @AfterEach
  void cleanup() {
    if (server != null) {
      server.stop();
    }
    clearDatabase();
  }

  @Test
  void encryptedSessionFetchesTracksAndDeliversCatalogLabeledNotification() {
    AccountClass owner = createAccountClass(1, 77, "Synthetic 2A", "/synthetic-app/");
    subscriptions.enable(owner.appUserId(), owner.catalogClassId());
    server.stubFor(
        post(urlPathEqualTo(owner.applicationPath() + SCHEDULE_ENDPOINT))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader(
                        "Set-Cookie", "rotated=after-weekly; Path=" + owner.applicationPath())
                    .withBody(text("schedule-normal"))));

    Collection<MonitoringTarget> targets = targetProvider.activeTargets();
    List<TrackingScope> planned = new MonitoringScopePlanner(clock).plan(targets);
    assertThat(targets)
        .containsExactly(
            new MonitoringTarget(owner.accountId(), owner.catalogClassId(), owner.journalId()));
    assertThat(planned).hasSize(2);
    TrackingScope current = planned.getFirst();
    assertThat(current.weekStart()).isEqualTo(WEEK_START);

    var result = productionCoordinator().refreshSuccessfulWeek(current);

    assertThat(result.baselineEstablishedNow()).isTrue();
    assertThat(sessions.loadCurrent(owner.accountId()).snapshotMaterial().cookieHeader())
        .contains("rotated=after-weekly");
    byte[] ciphertext =
        jdbc.queryForObject(
            "SELECT session_ciphertext FROM vulcan_account_secret WHERE account_id = ?",
            byte[].class,
            owner.accountId());
    assertThat(new String(ciphertext, StandardCharsets.UTF_8))
        .doesNotContain("seed=before", "rotated=after-weekly");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
        .isOne();

    var delivery =
        new TelegramNotificationDeliveryGateway(
            recipients, catalog, new TelegramNotificationFormatter(), telegram);
    var dispatch = new NotificationOutboxDispatcher(outboxStore, delivery, clock).dispatchOnce();

    assertThat(dispatch.delivered()).isOne();
    assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox", String.class))
        .isEqualTo("DELIVERED");
    assertThat(telegram.sent)
        .singleElement()
        .extracting(Sent::privateChatId)
        .isEqualTo(owner.privateChatId());
    assertThat(telegram.sent.getFirst().text())
        .contains("Synthetic 2A")
        .doesNotContain("Journal:", "Catalog class:", "Recipient:", "changeKey", "lessonPeriodId");
  }

  @Test
  void sameJournalTwoAccountsSelectSeparateSessionsScopesAndRecipients() {
    AccountClass ownerA = createAccountClass(10, 77, "Synthetic class A", "/account-a/");
    AccountClass ownerB = createAccountClass(20, 77, "Synthetic class B", "/account-b/");
    subscriptions.enable(ownerA.appUserId(), ownerA.catalogClassId());
    subscriptions.enable(ownerB.appUserId(), ownerB.catalogClassId());
    stubSchedule(ownerA);
    stubSchedule(ownerB);

    List<MonitoringTarget> targets = new ArrayList<>(targetProvider.activeTargets());
    List<TrackingScope> currentScopes =
        new MonitoringScopePlanner(clock)
            .plan(targets).stream().filter(scope -> scope.weekStart().equals(WEEK_START)).toList();
    var coordinator = productionCoordinator();
    currentScopes.forEach(coordinator::refreshSuccessfulWeek);

    assertThat(targets)
        .extracting(
            MonitoringTarget::vulcanAccountId,
            MonitoringTarget::catalogClassId,
            MonitoringTarget::journalId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(ownerA.accountId(), ownerA.catalogClassId(), 77L),
            org.assertj.core.groups.Tuple.tuple(ownerB.accountId(), ownerB.catalogClassId(), 77L));
    assertThat(
            jdbc.queryForList(
                "SELECT catalog_class_id FROM tracking_scope ORDER BY catalog_class_id",
                Long.class))
        .containsExactly(ownerA.catalogClassId(), ownerB.catalogClassId());
    assertThat(
            jdbc.queryForList(
                """
                SELECT catalog_class_id || ':' || recipient_user_id
                FROM notification_outbox ORDER BY catalog_class_id
                """,
                String.class))
        .containsExactly(
            ownerA.catalogClassId() + ":" + ownerA.appUserId(),
            ownerB.catalogClassId() + ":" + ownerB.appUserId());
    server.verify(
        1, postRequestedFor(urlPathEqualTo(ownerA.applicationPath() + SCHEDULE_ENDPOINT)));
    server.verify(
        1, postRequestedFor(urlPathEqualTo(ownerB.applicationPath() + SCHEDULE_ENDPOINT)));

    var delivery =
        new TelegramNotificationDeliveryGateway(
            recipients, catalog, new TelegramNotificationFormatter(), telegram);
    assertThat(
            new NotificationOutboxDispatcher(outboxStore, delivery, clock)
                .dispatchOnce()
                .delivered())
        .isEqualTo(2);
    assertThat(telegram.sent)
        .extracting(Sent::privateChatId, Sent::text)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                ownerA.privateChatId(), telegram.sent.get(0).text()),
            org.assertj.core.groups.Tuple.tuple(
                ownerB.privateChatId(), telegram.sent.get(1).text()));
    assertThat(telegram.sent.get(0).text())
        .contains("Synthetic class A")
        .doesNotContain("Synthetic class B");
    assertThat(telegram.sent.get(1).text())
        .contains("Synthetic class B")
        .doesNotContain("Synthetic class A");
  }

  @Test
  void rememberedAccountAutomaticallyRecoversOnceThenTracksTheSuccessfulRetry() {
    AccountClass owner = createAccountClass(30, 77, "Synthetic recovered", "/recover/");
    subscriptions.enable(owner.appUserId(), owner.catalogClassId());
    URI applicationUri = URI.create(server.baseUrl() + owner.applicationPath());
    try (RememberedCredentials credentials =
        new RememberedCredentials(
            URI.create("https://synthetic.invalid/"),
            "synthetic-login",
            "synthetic-password".toCharArray())) {
      secretStore.replace(
          owner.accountId(),
          new VulcanSessionMaterial(
              applicationUri, applicationUri, "expired-token", "expired-guid", "session=expired"),
          credentials,
          NOW);
    }
    fakes.recoveredMaterial.set(
        new VulcanSessionMaterial(
            applicationUri,
            applicationUri,
            "recovered-token",
            "recovered-guid",
            "session=recovered"));
    server.stubFor(
        post(urlPathEqualTo(owner.applicationPath() + SCHEDULE_ENDPOINT))
            .inScenario("automatic-recovery")
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("recovered")
            .willReturn(aResponse().withStatus(401)));
    server.stubFor(
        post(urlPathEqualTo(owner.applicationPath() + SCHEDULE_ENDPOINT))
            .inScenario("automatic-recovery")
            .whenScenarioStateIs("recovered")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader(
                        "Set-Cookie", "rotated=after-recovery; Path=" + owner.applicationPath())
                    .withBody(text("schedule-normal"))));
    TrackingScope scope =
        new MonitoringScopePlanner(clock).plan(targetProvider.activeTargets()).getFirst();

    var result = productionCoordinator().refreshSuccessfulWeek(scope);

    assertThat(result.baselineEstablishedNow()).isTrue();
    assertThat(fakes.authenticationCalls).hasValue(1);
    server.verify(2, postRequestedFor(urlPathEqualTo(owner.applicationPath() + SCHEDULE_ENDPOINT)));
    assertThat(sessions.loadCurrent(owner.accountId()).snapshotMaterial().cookieHeader())
        .contains("session=recovered", "rotated=after-recovery")
        .doesNotContain("session=expired");
    assertThat(
            jdbc.queryForMap(
                "SELECT status, remember_credentials FROM vulcan_account WHERE id = ?",
                owner.accountId()))
        .containsEntry("status", "CONNECTED")
        .containsEntry("remember_credentials", true);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking_scope", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
        .isOne();
  }

  @Test
  void missingRememberedCredentialsMarksReconnectAndCannotResolveExistingState() {
    AccountClass owner = createAccountClass(40, 77, "Synthetic reconnect", "/no-credentials/");
    subscriptions.enable(owner.appUserId(), owner.catalogClassId());
    server.stubFor(
        post(urlPathEqualTo(owner.applicationPath() + SCHEDULE_ENDPOINT))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(text("schedule-substitution"))));
    TrackingScope scope =
        new MonitoringScopePlanner(clock).plan(targetProvider.activeTargets()).getFirst();
    var coordinator = productionCoordinator();
    coordinator.refreshSuccessfulWeek(scope);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
        .isOne();

    server.resetAll();
    server.stubFor(
        post(urlPathEqualTo(owner.applicationPath() + SCHEDULE_ENDPOINT))
            .willReturn(aResponse().withStatus(401)));

    assertThatThrownBy(() -> coordinator.refreshSuccessfulWeek(scope))
        .isInstanceOfSatisfying(
            ScheduleSourceException.class,
            failure ->
                assertThat(failure.kind()).isEqualTo(SourceFailureKind.AUTHENTICATION_REQUIRED));

    assertThat(fakes.authenticationCalls).hasValue(0);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM vulcan_account WHERE id = ?", String.class, owner.accountId()))
        .isEqualTo("RECONNECT_REQUIRED");
    assertThat(targetProvider.activeTargets()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE event_type = 'CHANGE_RESOLVED'",
                Integer.class))
        .isZero();
  }

  private void stubSchedule(AccountClass owner) {
    server.stubFor(
        post(urlPathEqualTo(owner.applicationPath() + SCHEDULE_ENDPOINT))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(text("schedule-normal"))));
  }

  private ScheduleRefreshCoordinator productionCoordinator() {
    var persisted = new PersistedAccountWeeklyScheduleSource(sessions);
    var resilient =
        new ResilientWeeklyScheduleSource(
            persisted,
            ignored -> {},
            new RateLimitBackoffGate(clock),
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10));
    var recovering = new RecoveringAccountWeeklyScheduleSource(resilient, sessions);
    return new ScheduleRefreshCoordinator(recovering::fetchCompleteWeeklySnapshot, tracker);
  }

  private AccountClass createAccountClass(
      int suffix, long journalId, String className, String applicationPath) {
    long telegramUserId = 8_100_000_000L + suffix;
    long privateChatId = 9_100_000_000L + suffix;
    ApplicationUser user = identities.registerOrUpdate(telegramUserId, privateChatId);
    long accountId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_account
              (app_user_id, status, remember_credentials, created_at, updated_at, authenticated_at)
            VALUES (?, 'CONNECTED', FALSE, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            user.id(),
            Timestamp.from(NOW),
            Timestamp.from(NOW),
            Timestamp.from(NOW));
    URI applicationUri = URI.create(server.baseUrl() + applicationPath);
    secretStore.replace(
        accountId,
        new VulcanSessionMaterial(
            applicationUri, applicationUri, "synthetic-token", "synthetic-guid", "seed=before"),
        null,
        NOW);
    long catalogClassId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_class_catalog
              (vulcan_account_id, journal_id, class_id, name, school_unit, school_year,
               active, synced_at)
            VALUES (?, ?, ?, ?, 'Synthetic school', 2099, TRUE, ?)
            RETURNING id
            """,
            Long.class,
            accountId,
            journalId,
            10_000L + suffix,
            className,
            Timestamp.from(NOW));
    return new AccountClass(
        user.id(), accountId, catalogClassId, journalId, privateChatId, applicationPath);
  }

  private void clearDatabase() {
    jdbc.update("DELETE FROM notification_outbox");
    jdbc.update("DELETE FROM schedule_change_state");
    jdbc.update("DELETE FROM tracking_scope");
    jdbc.update("DELETE FROM monitoring_subscription");
    jdbc.update("DELETE FROM vulcan_class_catalog");
    jdbc.update("DELETE FROM vulcan_account_secret");
    jdbc.update("DELETE FROM vulcan_connect_token");
    jdbc.update("DELETE FROM vulcan_account");
    jdbc.update("DELETE FROM telegram_identity");
    jdbc.update("DELETE FROM app_user");
  }

  private record AccountClass(
      long appUserId,
      long accountId,
      long catalogClassId,
      long journalId,
      long privateChatId,
      String applicationPath) {}

  private record Sent(long privateChatId, String text) {}

  private static final class RecordingTelegramTransport implements TelegramMessageTransport {

    private final List<Sent> sent = new ArrayList<>();

    @Override
    public void sendPlainText(long privateChatId, String text) {
      sent.add(new Sent(privateChatId, text));
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {

    private final AtomicInteger authenticationCalls = new AtomicInteger();
    private final AtomicReference<VulcanSessionMaterial> recoveredMaterial =
        new AtomicReference<>();

    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    @Bean
    @Primary
    VulcanBrowserAuthenticator fakeBrowserAuthenticator() {
      return request -> {
        VulcanSessionMaterial material = recoveredMaterial.get();
        if (material == null) {
          throw new AssertionError("browser authentication must not run");
        }
        authenticationCalls.incrementAndGet();
        return material;
      };
    }

    @Bean
    @Primary
    VulcanSessionVerifier fakeSessionVerifier() {
      return material -> new VerifiedVulcanSession(material, List.of());
    }
  }
}
