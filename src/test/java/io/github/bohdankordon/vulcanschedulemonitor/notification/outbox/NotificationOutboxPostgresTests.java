package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDeliveryException;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDispatchPolicy;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class NotificationOutboxPostgresTests extends PostgresIntegrationTestSupport {

  private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
  private static final LocalDate WEEK_START = LocalDate.of(2026, 9, 7);
  private static final LocalDate WEEK_END = WEEK_START.plusDays(6);
  private static final Duration LEASE = Duration.ofMinutes(2);

  @Autowired private JdbcTemplate jdbc;
  @Autowired private NotificationOutboxStore store;

  private final MutableClock clock = new MutableClock(NOW);
  private long recipientUserId;

  @BeforeEach
  void clearDatabase() {
    jdbc.update("DELETE FROM notification_outbox");
    jdbc.update("DELETE FROM monitoring_subscription");
    jdbc.update("DELETE FROM telegram_identity");
    jdbc.update("DELETE FROM app_user");
    jdbc.update("DELETE FROM tracking_scope");
    recipientUserId =
        jdbc.queryForObject(
            """
            INSERT INTO app_user (active, created_at, updated_at)
            VALUES (TRUE, ?, ?)
            RETURNING id
            """,
            Long.class,
            Timestamp.from(NOW),
            Timestamp.from(NOW));
    clock.setInstant(NOW);
  }

  @Test
  void claimSelectsOnlyDueOrStaleRowsAndUpdatesDurableLeaseState() {
    long due = insert("PENDING", 0, NOW, null, null, null);
    long future = insert("PENDING", 0, NOW.plusSeconds(1), null, null, null);
    long delivered = insert("DELIVERED", 1, NOW, null, null, NOW);
    long dead = insert("DEAD", 1, NOW, null, null, null);
    long activeClaim = insert("IN_FLIGHT", 1, NOW, NOW.plusSeconds(1), UUID.randomUUID(), null);
    long staleClaim = insert("IN_FLIGHT", 1, NOW, NOW.minusSeconds(1), UUID.randomUUID(), null);

    List<NotificationOutboxClaim> claims = store.claimDue(NOW, 10, LEASE);

    assertThat(claims).extracting(claim -> claim.message().id()).containsExactly(due, staleClaim);
    assertThat(claims).extracting(claim -> claim.message().attemptNumber()).containsExactly(1, 2);
    assertThat(row(due))
        .containsEntry("status", "IN_FLIGHT")
        .containsEntry("attempt_count", 1)
        .containsEntry("lease_until", Timestamp.from(NOW.plus(LEASE)));
    assertThat(row(staleClaim))
        .containsEntry("status", "IN_FLIGHT")
        .containsEntry("attempt_count", 2)
        .containsEntry("lease_until", Timestamp.from(NOW.plus(LEASE)));
    assertThat(row(future)).containsEntry("status", "PENDING");
    assertThat(row(delivered)).containsEntry("status", "DELIVERED");
    assertThat(row(dead)).containsEntry("status", "DEAD");
    assertThat(row(activeClaim))
        .containsEntry("status", "IN_FLIGHT")
        .containsEntry("attempt_count", 1);
  }

  @Test
  void claimOwnershipTokenRejectsAcknowledgementFromAnOlderLease() {
    long id = insert("PENDING", 0, NOW, null, null, null);
    NotificationOutboxClaim first = store.claimDue(NOW, 1, LEASE).getFirst();
    NotificationOutboxClaim reclaimed =
        store.claimDue(NOW.plus(LEASE).plusSeconds(1), 1, LEASE).getFirst();

    assertThat(store.markDelivered(id, first.ownershipToken(), NOW.plusSeconds(1))).isFalse();
    assertThat(row(id)).containsEntry("status", "IN_FLIGHT").containsEntry("attempt_count", 2);
    assertThat(store.markDelivered(id, reclaimed.ownershipToken(), NOW.plus(LEASE).plusSeconds(2)))
        .isTrue();
    assertThat(row(id))
        .containsEntry("status", "DELIVERED")
        .containsEntry("delivered_at", Timestamp.from(NOW.plus(LEASE).plusSeconds(2)))
        .containsEntry("lease_until", null)
        .containsEntry("claim_token", null);
  }

  @Test
  void concurrentWorkersCannotClaimTheSameEventTwice() throws Exception {
    long id = insert("PENDING", 0, NOW, null, null, null);
    CountDownLatch start = new CountDownLatch(1);

    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          workers.submit(
              () -> {
                start.await();
                return store.claimDue(NOW, 1, LEASE);
              });
      var second =
          workers.submit(
              () -> {
                start.await();
                return store.claimDue(NOW, 1, LEASE);
              });
      start.countDown();

      List<NotificationOutboxClaim> combined = new ArrayList<>();
      combined.addAll(first.get(5, TimeUnit.SECONDS));
      combined.addAll(second.get(5, TimeUnit.SECONDS));
      assertThat(combined)
          .singleElement()
          .satisfies(claim -> assertThat(claim.message().id()).isEqualTo(id));
    }

    assertThat(row(id)).containsEntry("status", "IN_FLIGHT").containsEntry("attempt_count", 1);
  }

  @Test
  void dispatchSuccessAcknowledgesWithoutExposingPersistenceEntities() {
    long id = insert("PENDING", 0, NOW, null, null, null);
    List<NotificationOutboxMessage> received = new ArrayList<>();
    AtomicBoolean deliveryTransactionActive = new AtomicBoolean(true);
    NotificationOutboxDispatcher dispatcher =
        dispatcher(
            message -> {
              deliveryTransactionActive.set(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              received.add(message);
            });

    var summary = dispatcher.dispatchOnce();

    assertThat(summary.claimed()).isOne();
    assertThat(summary.delivered()).isOne();
    assertThat(summary.retried()).isZero();
    assertThat(summary.dead()).isZero();
    assertThat(deliveryTransactionActive).isFalse();
    assertThat(received)
        .singleElement()
        .satisfies(
            message -> {
              assertThat(message.id()).isEqualTo(id);
              assertThat(message.recipientUserId()).isEqualTo(recipientUserId);
              assertThat(message.eventType()).isEqualTo(NotificationEventType.BASELINE_ESTABLISHED);
              assertThat(message.activeChangeCount()).isZero();
              assertThat(message.attemptNumber()).isOne();
              assertThat(message.getClass().getName()).doesNotContain("persistence");
            });
    assertThat(row(id))
        .containsEntry("status", "DELIVERED")
        .containsEntry("delivered_at", Timestamp.from(NOW))
        .containsEntry("lease_until", null)
        .containsEntry("claim_token", null);
  }

  @Test
  void retryableFailureSchedulesBackoffAndCannotBeClaimedEarly() {
    long id = insert("PENDING", 0, NOW, null, null, null);
    NotificationOutboxDispatcher dispatcher =
        dispatcher(
            message -> {
              throw NotificationDeliveryException.retryable();
            });

    var summary = dispatcher.dispatchOnce();

    assertThat(summary.retried()).isOne();
    assertThat(row(id))
        .containsEntry("status", "PENDING")
        .containsEntry("next_attempt_at", Timestamp.from(NOW.plusSeconds(5)))
        .containsEntry("lease_until", null)
        .containsEntry("claim_token", null)
        .containsEntry("last_failure_category", "RETRYABLE");
    assertThat(store.claimDue(NOW.plusSeconds(4), 1, LEASE)).isEmpty();
    assertThat(store.claimDue(NOW.plusSeconds(5), 1, LEASE)).hasSize(1);
  }

  @Test
  void providerRetryAfterCanOnlyDelayTheNextAttempt() {
    long id = insert("PENDING", 0, NOW, null, null, null);
    NotificationOutboxDispatcher dispatcher =
        dispatcher(
            message -> {
              throw NotificationDeliveryException.retryable(Duration.ofSeconds(30));
            });

    dispatcher.dispatchOnce();

    assertThat(row(id))
        .containsEntry("status", "PENDING")
        .containsEntry("next_attempt_at", Timestamp.from(NOW.plusSeconds(30)));
  }

  @Test
  void permanentFailureMovesEventDirectlyToDead() {
    long id = insert("PENDING", 0, NOW, null, null, null);
    NotificationOutboxDispatcher dispatcher =
        dispatcher(
            message -> {
              throw NotificationDeliveryException.permanent();
            });

    var summary = dispatcher.dispatchOnce();

    assertThat(summary.dead()).isOne();
    assertThat(row(id))
        .containsEntry("status", "DEAD")
        .containsEntry("last_failure_category", "PERMANENT")
        .containsEntry("lease_until", null)
        .containsEntry("claim_token", null);
    assertThat(store.claimDue(NOW.plus(Duration.ofDays(1)), 1, LEASE)).isEmpty();
  }

  @Test
  void finalAllowedAttemptStillReachesGatewayAndCanBeDelivered() {
    long id = insert("PENDING", 4, NOW, null, null, null);
    AtomicInteger gatewayCalls = new AtomicInteger();
    NotificationOutboxDispatcher dispatcher =
        dispatcher(
            message -> {
              gatewayCalls.incrementAndGet();
              assertThat(message.attemptNumber()).isEqualTo(5);
            });

    var summary = dispatcher.dispatchOnce();

    assertThat(gatewayCalls).hasValue(1);
    assertThat(summary.claimed()).isOne();
    assertThat(summary.delivered()).isOne();
    assertThat(summary.dead()).isZero();
    assertThat(row(id))
        .containsEntry("status", "DELIVERED")
        .containsEntry("attempt_count", 5)
        .containsEntry("delivered_at", Timestamp.from(NOW));
  }

  @Test
  void staleFinalAttemptIsReclaimedForCleanupWithoutSixthGatewayCall() {
    long id = insert("IN_FLIGHT", 5, NOW, NOW.minusSeconds(1), UUID.randomUUID(), null);
    AtomicInteger gatewayCalls = new AtomicInteger();
    NotificationOutboxDispatcher dispatcher = dispatcher(message -> gatewayCalls.incrementAndGet());

    var summary = dispatcher.dispatchOnce();

    assertThat(gatewayCalls).hasValue(0);
    assertThat(summary.claimed()).isOne();
    assertThat(summary.delivered()).isZero();
    assertThat(summary.dead()).isOne();
    assertThat(row(id))
        .containsEntry("status", "DEAD")
        .containsEntry("attempt_count", 6)
        .containsEntry("last_failure_category", "EXHAUSTED")
        .containsEntry("lease_until", null)
        .containsEntry("claim_token", null);
    assertThat(store.claimDue(NOW.plus(Duration.ofDays(1)), 1, LEASE)).isEmpty();
  }

  @Test
  void finalRetryableAndUnexpectedFailuresExhaustTheAttemptBudget() {
    long retryableId = insert("PENDING", 4, NOW, null, null, null);
    NotificationOutboxDispatcher retryable =
        dispatcher(
            message -> {
              throw NotificationDeliveryException.retryable();
            });

    assertThat(retryable.dispatchOnce().dead()).isOne();
    assertThat(row(retryableId))
        .containsEntry("status", "DEAD")
        .containsEntry("attempt_count", 5)
        .containsEntry("last_failure_category", "RETRYABLE");

    long unexpectedId = insert("PENDING", 4, NOW, null, null, null);
    NotificationOutboxDispatcher unexpected =
        dispatcher(
            message -> {
              throw new IllegalStateException("provider detail must not persist");
            });

    assertThat(unexpected.dispatchOnce().dead()).isOne();
    assertThat(row(unexpectedId))
        .containsEntry("status", "DEAD")
        .containsEntry("attempt_count", 5)
        .containsEntry("last_failure_category", "UNEXPECTED");
    assertThat(jdbc.queryForList("SELECT * FROM notification_outbox").toString())
        .doesNotContain("provider detail");
  }

  @Test
  void abandonedClaimSurvivesAndCanBeReclaimedAfterLeaseExpiry() {
    long id = insert("PENDING", 0, NOW, null, null, null);

    assertThat(store.claimDue(NOW, 1, LEASE)).hasSize(1);
    assertThat(store.claimDue(NOW.plus(LEASE).minusMillis(1), 1, LEASE)).isEmpty();
    assertThat(store.claimDue(NOW.plus(LEASE), 1, LEASE))
        .singleElement()
        .satisfies(claim -> assertThat(claim.message().attemptNumber()).isEqualTo(2));
    assertThat(row(id)).containsEntry("status", "IN_FLIGHT").containsEntry("attempt_count", 2);
  }

  @Test
  void emptyAndBoundedBatchDispatchesMakeOnlyExpectedGatewayCalls() {
    AtomicInteger calls = new AtomicInteger();
    NotificationOutboxDispatcher empty = dispatcher(message -> calls.incrementAndGet());

    assertThat(empty.dispatchOnce().claimed()).isZero();
    assertThat(calls).hasValue(0);

    insert("PENDING", 0, NOW, null, null, null);
    insert("PENDING", 0, NOW, null, null, null);
    insert("PENDING", 0, NOW, null, null, null);
    NotificationDispatchPolicy bounded =
        new NotificationDispatchPolicy(2, LEASE, 5, Duration.ofMinutes(15));
    NotificationOutboxDispatcher batch =
        new NotificationOutboxDispatcher(store, message -> calls.incrementAndGet(), clock, bounded);

    assertThat(batch.dispatchOnce().claimed()).isEqualTo(2);
    assertThat(calls).hasValue(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE status = 'PENDING'", Integer.class))
        .isOne();
  }

  @Test
  void recipientlessTerminalHistoryIsNeverClaimed() {
    jdbc.update(
        """
        INSERT INTO notification_outbox
          (event_type, journal_id, week_start, week_end, active_change_count, status,
           attempt_count, next_attempt_at, created_at, last_failure_category)
        VALUES ('BASELINE_ESTABLISHED', 42, ?, ?, 0, 'DEAD', 0, ?, ?, 'UNROUTABLE')
        """,
        WEEK_START,
        WEEK_END,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
    long actionable = insert("PENDING", 0, NOW, null, null, null);

    assertThat(store.claimDue(NOW, 10, LEASE))
        .singleElement()
        .satisfies(claim -> assertThat(claim.message().id()).isEqualTo(actionable));
  }

  private NotificationOutboxDispatcher dispatcher(
      io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDeliveryGateway
          gateway) {
    return new NotificationOutboxDispatcher(store, gateway, clock);
  }

  private long insert(
      String status,
      int attemptCount,
      Instant nextAttemptAt,
      Instant leaseUntil,
      UUID claimToken,
      Instant deliveredAt) {
    return jdbc.queryForObject(
        """
        INSERT INTO notification_outbox
          (event_type, journal_id, week_start, week_end, active_change_count, recipient_user_id, status,
           attempt_count, next_attempt_at, lease_until, claim_token, created_at, delivered_at)
        VALUES ('BASELINE_ESTABLISHED', 42, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """,
        Long.class,
        WEEK_START,
        WEEK_END,
        recipientUserId,
        status,
        attemptCount,
        Timestamp.from(nextAttemptAt),
        leaseUntil == null ? null : Timestamp.from(leaseUntil),
        claimToken,
        Timestamp.from(NOW),
        deliveredAt == null ? null : Timestamp.from(deliveredAt));
  }

  private Map<String, Object> row(long id) {
    return jdbc.queryForMap("SELECT * FROM notification_outbox WHERE id = ?", id);
  }

  static final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("Test clock only supports UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
