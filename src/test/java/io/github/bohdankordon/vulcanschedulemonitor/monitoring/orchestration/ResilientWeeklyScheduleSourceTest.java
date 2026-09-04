package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ResilientWeeklyScheduleSourceTest {

  private static final TrackingScope SCOPE =
      new TrackingScope(42L, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13));
  private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");

  @AfterEach
  void clearInterruptFlag() {
    Thread.interrupted();
  }

  @Test
  void transportFailureRetriesWithBoundedExponentialBackoff() {
    AtomicInteger calls = new AtomicInteger();
    RecordingDelay delays = new RecordingDelay();
    WeeklyScheduleSource delegate =
        ignored -> {
          if (calls.incrementAndGet() < 3) {
            throw VulcanHttpException.transportFailure("schedule");
          }
          return snapshot();
        };

    ScheduleSnapshot result =
        resilient(delegate, delays, new MutableClock(NOW)).fetchCompleteWeeklySnapshot(SCOPE);

    assertThat(result).isEqualTo(snapshot());
    assertThat(calls).hasValue(3);
    assertThat(delays.values).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
  }

  @Test
  void serverErrorsAreRetryableAndStopAtMaximumAttempts() {
    for (int status : List.of(500, 503)) {
      AtomicInteger calls = new AtomicInteger();
      RecordingDelay delays = new RecordingDelay();
      WeeklyScheduleSource delegate =
          ignored -> {
            calls.incrementAndGet();
            throw VulcanHttpException.responseFailure("schedule", status);
          };

      assertThatThrownBy(
              () ->
                  resilient(delegate, delays, new MutableClock(NOW))
                      .fetchCompleteWeeklySnapshot(SCOPE))
          .isInstanceOfSatisfying(
              ScheduleSourceException.class,
              failure ->
                  assertThat(failure.kind())
                      .isEqualTo(SourceFailureKind.TRANSIENT_FAILURE_EXHAUSTED));
      assertThat(calls).hasValue(3);
      assertThat(delays.values).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    }
  }

  @Test
  void permanentClientFailureDoesNotRetry() {
    assertSingleAttemptFailure(
        VulcanHttpException.responseFailure("schedule", 400), SourceFailureKind.PERMANENT_FAILURE);
  }

  @Test
  void authenticationFailuresDoNotRetry() {
    for (int status : List.of(401, 403)) {
      assertSingleAttemptFailure(
          VulcanHttpException.responseFailure("schedule", status),
          SourceFailureKind.AUTHENTICATION_REQUIRED);
    }
  }

  @Test
  void redirectsDoNotRetryAsTransientFailures() {
    assertSingleAttemptFailure(
        VulcanHttpException.responseFailure("schedule", 302),
        SourceFailureKind.AUTHENTICATION_REQUIRED);
  }

  @Test
  void unexpectedHtmlDoesNotRetryAndRequiresSessionRecovery() {
    assertSingleAttemptFailure(
        VulcanHttpException.unexpectedHtml("schedule"), SourceFailureKind.AUTHENTICATION_REQUIRED);
  }

  @Test
  void protocolFailureDoesNotRetry() {
    assertSingleAttemptFailure(
        new VulcanProtocolException("schedule"), SourceFailureKind.PROTOCOL_FAILURE);
  }

  @Test
  void interruptedRetryDelayRestoresInterruptStateAndAborts() {
    WeeklyScheduleSource delegate =
        ignored -> {
          throw VulcanHttpException.transportFailure("schedule");
        };
    DelayStrategy interrupted =
        ignored -> {
          throw new InterruptedException("synthetic");
        };

    assertThatThrownBy(
            () ->
                resilient(delegate, interrupted, new MutableClock(NOW))
                    .fetchCompleteWeeklySnapshot(SCOPE))
        .isInstanceOfSatisfying(
            ScheduleSourceException.class,
            failure -> assertThat(failure.kind()).isEqualTo(SourceFailureKind.INTERRUPTED));
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  void shortRateLimitWaitsAtLeastRequiredDelayThenReturnsSuccessfulRetry() {
    AtomicInteger calls = new AtomicInteger();
    MutableClock clock = new MutableClock(NOW);
    RecordingDelay delays = new RecordingDelay(clock);
    WeeklyScheduleSource delegate =
        ignored -> {
          if (calls.incrementAndGet() == 1) {
            throw VulcanHttpException.responseFailure("schedule", 429, Duration.ofSeconds(4));
          }
          return snapshot();
        };

    assertThat(resilient(delegate, delays, clock).fetchCompleteWeeklySnapshot(SCOPE))
        .isEqualTo(snapshot());
    assertThat(calls).hasValue(2);
    assertThat(delays.values).containsExactly(Duration.ofSeconds(4));
  }

  @Test
  void absentRetryAfterUsesFallbackDelay() {
    AtomicInteger calls = new AtomicInteger();
    MutableClock clock = new MutableClock(NOW);
    RecordingDelay delays = new RecordingDelay(clock);
    WeeklyScheduleSource delegate =
        ignored -> {
          if (calls.incrementAndGet() == 1) {
            throw VulcanHttpException.responseFailure("schedule", 429);
          }
          return snapshot();
        };

    resilient(delegate, delays, clock).fetchCompleteWeeklySnapshot(SCOPE);

    assertThat(delays.values).containsExactly(Duration.ofSeconds(5));
  }

  @Test
  void longRateLimitDefersLaterCallsUntilGateExpires() {
    AtomicInteger calls = new AtomicInteger();
    MutableClock clock = new MutableClock(NOW);
    WeeklyScheduleSource delegate =
        ignored -> {
          if (calls.incrementAndGet() == 1) {
            throw VulcanHttpException.responseFailure("schedule", 429, Duration.ofMinutes(2));
          }
          return snapshot();
        };
    ResilientWeeklyScheduleSource source = resilient(delegate, new RecordingDelay(clock), clock);

    assertThatThrownBy(() -> source.fetchCompleteWeeklySnapshot(SCOPE))
        .isInstanceOfSatisfying(
            ScheduleSourceException.class,
            failure -> {
              assertThat(failure.kind()).isEqualTo(SourceFailureKind.DEFERRED_RATE_LIMIT);
              assertThat(failure.deferredUntil()).contains(NOW.plus(Duration.ofMinutes(2)));
            });
    assertThatThrownBy(() -> source.fetchCompleteWeeklySnapshot(SCOPE))
        .isInstanceOfSatisfying(
            ScheduleSourceException.class,
            failure -> assertThat(failure.kind()).isEqualTo(SourceFailureKind.DEFERRED_RATE_LIMIT));
    assertThat(calls).hasValue(1);

    clock.advance(Duration.ofMinutes(2));
    assertThat(source.fetchCompleteWeeklySnapshot(SCOPE)).isEqualTo(snapshot());
    assertThat(calls).hasValue(2);
  }

  @Test
  void serverRetryAfterIsNeverUndercutByExponentialBackoff() {
    AtomicInteger calls = new AtomicInteger();
    RecordingDelay delays = new RecordingDelay();
    WeeklyScheduleSource delegate =
        ignored -> {
          if (calls.incrementAndGet() == 1) {
            throw VulcanHttpException.responseFailure("schedule", 503, Duration.ofSeconds(3));
          }
          return snapshot();
        };

    resilient(delegate, delays, new MutableClock(NOW)).fetchCompleteWeeklySnapshot(SCOPE);

    assertThat(delays.values).containsExactly(Duration.ofSeconds(3));
  }

  private static void assertSingleAttemptFailure(
      RuntimeException sourceFailure, SourceFailureKind expectedKind) {
    AtomicInteger calls = new AtomicInteger();
    RecordingDelay delays = new RecordingDelay();
    WeeklyScheduleSource delegate =
        ignored -> {
          calls.incrementAndGet();
          throw sourceFailure;
        };

    assertThatThrownBy(
            () ->
                resilient(delegate, delays, new MutableClock(NOW))
                    .fetchCompleteWeeklySnapshot(SCOPE))
        .isInstanceOfSatisfying(
            ScheduleSourceException.class,
            failure -> assertThat(failure.kind()).isEqualTo(expectedKind));
    assertThat(calls).hasValue(1);
    assertThat(delays.values).isEmpty();
  }

  private static ResilientWeeklyScheduleSource resilient(
      WeeklyScheduleSource delegate, DelayStrategy delay, Clock clock) {
    return new ResilientWeeklyScheduleSource(
        delegate,
        delay,
        new RateLimitBackoffGate(clock),
        3,
        Duration.ofSeconds(1),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10));
  }

  private static ScheduleSnapshot snapshot() {
    return new ScheduleSnapshot(
        SCOPE.journalId(), SCOPE.weekStart(), SCOPE.weekEnd(), List.of(), List.of());
  }

  private static final class RecordingDelay implements DelayStrategy {

    private final List<Duration> values = new ArrayList<>();
    private final MutableClock clock;

    private RecordingDelay() {
      this.clock = null;
    }

    private RecordingDelay(MutableClock clock) {
      this.clock = clock;
    }

    @Override
    public void delay(Duration duration) {
      values.add(duration);
      if (clock != null) {
        clock.advance(duration);
      }
    }
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void advance(Duration duration) {
      instant = instant.plus(duration);
    }

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
