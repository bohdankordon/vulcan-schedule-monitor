package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeLifecycle;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleChangeTracker;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingResult;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.notification.recipient.NotificationRecipientProvider;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.LessonChangeContext;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(RecipientOutboxFanoutPostgresTests.FanoutTestConfiguration.class)
class RecipientOutboxFanoutPostgresTests extends PostgresIntegrationTestSupport {

  private static final long JOURNAL_ID = 42L;
  private static final LocalDate WEEK_START = LocalDate.of(2026, 9, 7);
  private static final LocalDate WEEK_END = WEEK_START.plusDays(6);
  private static final Instant FIRST = Instant.parse("2026-09-04T08:00:00Z");
  private static final Instant SECOND = Instant.parse("2026-09-04T09:00:00Z");
  private static final Instant THIRD = Instant.parse("2026-09-04T10:00:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ScheduleChangeTracker tracker;
  @Autowired private TelegramIdentityRegistration registration;
  @Autowired private MonitoringSubscriptionService subscriptions;
  @Autowired private MutableClock clock;
  @Autowired private FailingRecipientProvider recipients;

  private long accountId;
  private long catalogClassId;

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
    clock.setInstant(FIRST);
    recipients.reset();
  }

  @Test
  void baselineCreatesOneIntentPerActiveRecipientAndNoChangeSpam() {
    long firstUser = subscribe(1);

    TrackingResult result =
        tracker.reconcileSuccessfulSnapshot(scope(), snapshot(change("T2", "S2", 40L)));

    assertThat(result.baselineEstablishedNow()).isTrue();
    assertThat(result.transitions()).isEmpty();
    assertThat(outboxRows())
        .extracting(row -> row.get("event_type"))
        .containsExactly("BASELINE_ESTABLISHED");
    assertThat(outboxRows())
        .extracting(row -> ((Number) row.get("recipient_user_id")).longValue())
        .containsExactly(firstUser);
  }

  @Test
  void baselineWithoutRecipientsStillPersistsTrackingAndNoNotificationIntent() {
    createSelectableUser(2);
    TrackingResult result =
        tracker.reconcileSuccessfulSnapshot(scope(), snapshot(change("T2", "S2", 40L)));

    assertThat(result.baselineEstablishedNow()).isTrue();
    assertThat(
            jdbc.queryForObject("SELECT baseline_established FROM tracking_scope", Boolean.class))
        .isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_change_state", Integer.class))
        .isOne();
    assertThat(outboxRows()).isEmpty();
  }

  @Test
  void newUpdatedResolvedAndUnchangedTransitionsFanOutPerRecipient() {
    long firstUser = subscribe(3);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot());

    clock.setInstant(SECOND);
    ScheduleChange appeared = change("T2", "S2", 40L);
    assertThat(tracker.reconcileSuccessfulSnapshot(scope(), snapshot(appeared)).transitions())
        .extracting(transition -> transition.lifecycle())
        .containsExactly(ChangeLifecycle.NEW);
    assertFanout("CHANGE_NEW", firstUser);

    clock.setInstant(THIRD);
    ScheduleChange updated = change("T3", "S3", 40L);
    assertThat(tracker.reconcileSuccessfulSnapshot(scope(), snapshot(updated)).transitions())
        .extracting(transition -> transition.lifecycle())
        .containsExactly(ChangeLifecycle.UPDATED);
    assertFanout("CHANGE_UPDATED", firstUser);

    clock.setInstant(THIRD.plusSeconds(1));
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot(updated));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE event_type = 'CHANGE_UPDATED'",
                Integer.class))
        .isOne();

    clock.setInstant(THIRD.plusSeconds(2));
    assertThat(tracker.reconcileSuccessfulSnapshot(scope(), snapshot()).transitions())
        .extracting(transition -> transition.lifecycle())
        .containsExactly(ChangeLifecycle.RESOLVED);
    assertFanout("CHANGE_RESOLVED", firstUser);
  }

  @Test
  void transitionOrderIsPreservedForEachRecipient() {
    long firstUser = subscribe(5);
    ScheduleChange unchanged = change("T2", "S2", 40L);
    ScheduleChange beforeUpdate = change("T2", "S2", 41L);
    ScheduleChange resolved = change("T2", "S2", 42L);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot(unchanged, beforeUpdate, resolved));

    clock.setInstant(SECOND);
    ScheduleChange updated = change("T3", "S3", 41L);
    ScheduleChange appeared = change("T2", "S2", 43L);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot(unchanged, updated, appeared));

    assertThat(
            jdbc.queryForList(
                """
                SELECT recipient_user_id, event_type
                FROM notification_outbox
                WHERE event_type <> 'BASELINE_ESTABLISHED'
                ORDER BY id
                """))
        .containsExactly(
            Map.of("recipient_user_id", firstUser, "event_type", "CHANGE_UPDATED"),
            Map.of("recipient_user_id", firstUser, "event_type", "CHANGE_NEW"),
            Map.of("recipient_user_id", firstUser, "event_type", "CHANGE_RESOLVED"));
  }

  @Test
  void lateSubscriberReceivesOnlyFutureTransitions() {
    long firstUser = createSelectableUser(7);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot());
    subscriptions.enable(firstUser, catalogClassId);

    clock.setInstant(SECOND);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot(change("T2", "S2", 40L)));

    assertThat(
            jdbc.queryForList(
                "SELECT recipient_user_id FROM notification_outbox WHERE event_type = 'BASELINE_ESTABLISHED'",
                Long.class))
        .isEmpty();
    assertFanout("CHANGE_NEW", firstUser);
  }

  @Test
  void unsubscribePreservesExistingIntentAndStopsFutureFanout() {
    long firstUser = subscribe(9);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot());
    clock.setInstant(SECOND);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot(change("T2", "S2", 40L)));
    int existingIntentCount = outboxRows().size();

    subscriptions.disable(firstUser, catalogClassId);
    clock.setInstant(THIRD);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot(change("T3", "S3", 40L)));

    assertThat(outboxRows()).hasSize(existingIntentCount);
    assertFanout("CHANGE_UPDATED");
    assertThat(
            jdbc.queryForObject(
                """
                SELECT count(*) FROM notification_outbox
                WHERE recipient_user_id = ? AND event_type = 'CHANGE_NEW'
                """,
                Integer.class,
                firstUser))
        .isOne();
  }

  @Test
  void laterFanoutFailureRollsBackFirstInsertAndTrackingMutation() {
    long firstUser = subscribe(11);
    ScheduleChange original = change("T2", "S2", 40L);
    tracker.reconcileSuccessfulSnapshot(scope(), snapshot(original));
    Map<String, Object> before = trackingState();
    int outboxCountBefore = outboxRows().size();
    recipients.failAfterFirstRealRecipient();
    clock.setInstant(SECOND);

    assertThatThrownBy(
            () -> tracker.reconcileSuccessfulSnapshot(scope(), snapshot(change("T3", "S3", 40L))))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(trackingState()).isEqualTo(before);
    assertThat(outboxRows()).hasSize(outboxCountBefore);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE recipient_user_id = ?",
                Integer.class,
                firstUser))
        .isOne();
  }

  private long subscribe(int suffix) {
    long userId = createSelectableUser(suffix);
    subscriptions.enable(userId, catalogClassId);
    return userId;
  }

  private long createSelectableUser(int suffix) {
    long userId =
        registration.registerOrUpdate(7_000_000_000L + suffix, 8_000_000_000L + suffix).id();
    accountId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_account
              (app_user_id, status, remember_credentials, created_at, updated_at, authenticated_at)
            VALUES (?, 'CONNECTED', FALSE, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            userId,
            Timestamp.from(FIRST),
            Timestamp.from(FIRST),
            Timestamp.from(FIRST));
    catalogClassId =
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
            Timestamp.from(FIRST));
    return userId;
  }

  private void assertFanout(String eventType, long... expectedRecipients) {
    assertThat(
            jdbc.queryForList(
                """
                SELECT recipient_user_id
                FROM notification_outbox
                WHERE event_type = ?
                ORDER BY id
                """,
                Long.class,
                eventType))
        .containsExactly(java.util.Arrays.stream(expectedRecipients).boxed().toArray(Long[]::new));
  }

  private List<Map<String, Object>> outboxRows() {
    return jdbc.queryForList("SELECT * FROM notification_outbox ORDER BY id");
  }

  private TrackingScope scope() {
    return new TrackingScope(accountId, catalogClassId, JOURNAL_ID, WEEK_START, WEEK_END);
  }

  private Map<String, Object> trackingState() {
    return jdbc.queryForMap(
        """
        SELECT scope.last_success_at, state.fingerprint, state.first_seen_at, state.last_seen_at
        FROM tracking_scope scope
        JOIN schedule_change_state state ON state.scope_id = scope.id
        """);
  }

  private static ScheduleSnapshot snapshot(ScheduleChange... changes) {
    return new ScheduleSnapshot(JOURNAL_ID, WEEK_START, WEEK_END, List.of(), List.of(changes));
  }

  private static ScheduleChange change(
      String replacementTeacher, String replacementSubject, long groupId) {
    LessonOccurrence lesson =
        new LessonOccurrence(WEEK_START.plusDays(1), 3L, 10L, 20L, 30L, groupId);
    return new TeacherSubstitution(
        LessonChangeContext.matched(lesson, lesson), replacementTeacher, replacementSubject);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FanoutTestConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(FIRST);
    }

    @Bean
    @Primary
    FailingRecipientProvider failingRecipientProvider(
        @Qualifier("jpaSubscriptionRoutingProvider") NotificationRecipientProvider delegate) {
      return new FailingRecipientProvider(delegate);
    }
  }

  static final class FailingRecipientProvider implements NotificationRecipientProvider {

    private final NotificationRecipientProvider delegate;
    private boolean failAfterFirstRealRecipient;

    FailingRecipientProvider(NotificationRecipientProvider delegate) {
      this.delegate = delegate;
    }

    void failAfterFirstRealRecipient() {
      failAfterFirstRealRecipient = true;
    }

    void reset() {
      failAfterFirstRealRecipient = false;
    }

    @Override
    public List<Long> activeRecipientUserIds(long catalogClassId) {
      List<Long> actual = delegate.activeRecipientUserIds(catalogClassId);
      if (!failAfterFirstRealRecipient) {
        return actual;
      }
      failAfterFirstRealRecipient = false;
      List<Long> failing = new ArrayList<>();
      failing.add(actual.getFirst());
      failing.add(Long.MAX_VALUE);
      return List.copyOf(failing);
    }
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
