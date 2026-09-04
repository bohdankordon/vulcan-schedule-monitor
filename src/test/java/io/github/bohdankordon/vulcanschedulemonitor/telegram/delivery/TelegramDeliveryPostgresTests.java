package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleChangeTracker;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDispatchPolicy;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxStore;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.MutableClock;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientDirectory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.VulcanClassCatalog;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TelegramDeliveryPostgresTests.ClockConfiguration.class)
class TelegramDeliveryPostgresTests extends PostgresIntegrationTestSupport {

  private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
  private static final long JOURNAL_ID = 42L;
  private static final long TELEGRAM_USER_ID = 7_000_000_001L;
  private static final long PRIVATE_CHAT_ID = 8_000_000_001L;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ScheduleChangeTracker tracker;
  @Autowired private TelegramIdentityRegistration registration;
  @Autowired private TelegramRecipientDirectory recipients;
  @Autowired private MonitoringSubscriptionService subscriptions;
  @Autowired private NotificationOutboxStore store;
  @Autowired private MutableClock clock;
  @Autowired private VulcanClassCatalog catalog;

  @BeforeEach
  void clearDatabase() {
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

  @Test
  void recipientSpecificPostgresIntentIsDeliveredThroughTelegramGateway() {
    createBaselineIntent();
    var sends = new ArrayList<Send>();
    TelegramMessageTransport transport = (chat, text) -> sends.add(new Send(chat, text));
    var dispatcher = dispatcher(transport);

    var summary = dispatcher.dispatchOnce();

    assertThat(summary.delivered()).isOne();
    assertThat(sends)
        .containsExactly(
            new Send(
                PRIVATE_CHAT_ID,
                "Monitoring baseline established.\n"
                    + "Class: Synthetic 2A\n"
                    + "Week: 2026-08-31 to 2026-09-06\n"
                    + "Active changes: 0"));
    assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox", String.class))
        .isEqualTo("DELIVERED");
  }

  @Test
  void retryAfterGatePreventsPrematureClaimsAndAttemptsThenDeliveryResumes() {
    createBaselineIntent();
    var gate = new TelegramProviderAvailabilityGate(clock);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    TelegramMessageTransport transport =
        (chat, text) -> {
          if (calls.getAndIncrement() == 0) {
            gate.defer(Duration.ofSeconds(30));
            throw new TelegramTransportException(
                TelegramFailureCategory.RATE_LIMITED, Duration.ofSeconds(30));
          }
        };
    var scheduler = new TelegramNotificationDispatchScheduler(dispatcher(transport), gate);

    scheduler.dispatch();
    assertThat(outboxStatus()).isEqualTo("PENDING");
    assertThat(attemptCount()).isOne();
    scheduler.dispatch();
    scheduler.dispatch();
    assertThat(calls).hasValue(1);
    assertThat(attemptCount()).isOne();

    clock.advance(Duration.ofSeconds(30));
    scheduler.dispatch();
    assertThat(calls).hasValue(2);
    assertThat(outboxStatus()).isEqualTo("DELIVERED");
  }

  @Test
  void authenticationSuspensionLeavesIntentPendingWithoutFurtherClaims() {
    createBaselineIntent();
    var gate = new TelegramProviderAvailabilityGate(clock);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    TelegramMessageTransport transport =
        (chat, text) -> {
          calls.incrementAndGet();
          gate.suspendUntilRestart();
          throw new TelegramTransportException(TelegramFailureCategory.AUTHENTICATION, null);
        };
    var scheduler = new TelegramNotificationDispatchScheduler(dispatcher(transport), gate);

    scheduler.dispatch();
    assertThat(outboxStatus()).isEqualTo("PENDING");
    assertThat(attemptCount()).isOne();
    clock.advance(Duration.ofDays(1));
    scheduler.dispatch();

    assertThat(calls).hasValue(1);
    assertThat(attemptCount()).isOne();
    assertThat(outboxStatus()).isEqualTo("PENDING");
  }

  private void createBaselineIntent() {
    long appUserId = registration.registerOrUpdate(TELEGRAM_USER_ID, PRIVATE_CHAT_ID).id();
    long accountId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_account
              (app_user_id, status, remember_credentials, created_at, updated_at, authenticated_at)
            VALUES (?, 'CONNECTED', FALSE, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            appUserId,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
            Timestamp.from(NOW));
    long catalogClassId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_class_catalog
              (vulcan_account_id, journal_id, class_id, name, school_year, active, synced_at)
            VALUES (?, ?, 420, 'Synthetic 2A', 2026, TRUE, ?)
            RETURNING id
            """,
            Long.class,
            accountId,
            JOURNAL_ID,
            Timestamp.from(NOW));
    subscriptions.enable(appUserId, catalogClassId);
    tracker.reconcileSuccessfulSnapshot(
        new TrackingScope(
            accountId,
            catalogClassId,
            JOURNAL_ID,
            LocalDate.of(2026, 8, 31),
            LocalDate.of(2026, 9, 6)),
        new ScheduleSnapshot(
            JOURNAL_ID, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6), List.of(), List.of()));
    assertThat(outboxStatus()).isEqualTo("PENDING");
  }

  private NotificationOutboxDispatcher dispatcher(TelegramMessageTransport transport) {
    var gateway =
        new TelegramNotificationDeliveryGateway(
            recipients, catalog, new TelegramNotificationFormatter(), transport);
    return new NotificationOutboxDispatcher(
        store,
        gateway,
        clock,
        new NotificationDispatchPolicy(1, Duration.ofMinutes(2), 5, Duration.ofMinutes(15)));
  }

  private String outboxStatus() {
    return jdbc.queryForObject("SELECT status FROM notification_outbox", String.class);
  }

  private int attemptCount() {
    return jdbc.queryForObject("SELECT attempt_count FROM notification_outbox", Integer.class);
  }

  private record Send(long chatId, String text) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class ClockConfiguration {
    @Bean
    @Primary
    MutableClock mutableTelegramDeliveryClock() {
      return new MutableClock(NOW);
    }
  }
}
