package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleChangeTracker;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleRefreshCoordinator;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingResult;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionManager;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule.PersistedAccountWeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule.SessionWeeklyScheduleFetcher;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RecoveringAccountWeeklyScheduleSourceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-09T10:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate WEEK_START = LocalDate.of(2026, 9, 7);

  @Test
  void transientRecoveryFailureCannotReenterRecoveryThroughTheHttpRetryBudget() {
    VulcanSessionManager sessions = mock(VulcanSessionManager.class);
    VulcanSession expired = session("expired", "sid=old");
    AtomicInteger weeklyCalls = new AtomicInteger();
    when(sessions.loadCurrent(1)).thenReturn(expired);
    when(sessions.recover(1)).thenReturn(VulcanSessionManager.RecoveryResult.TRANSIENT_FAILURE);
    RecoveringAccountWeeklyScheduleSource source =
        composition(
            sessions,
            (ignored, journalId, weekStart) -> {
              weeklyCalls.incrementAndGet();
              throw VulcanHttpException.responseFailure("weekly", 401);
            },
            ignored -> {});
    ScheduleChangeTracker tracker = mock(ScheduleChangeTracker.class);

    MonitoringCycleSummary summary =
        runner(source, tracker, List.of(target(1, 101, 77))).runCycle();

    assertThat(summary.stoppedEarly()).isFalse();
    assertThat(summary.outcomes())
        .extracting(ScopeMonitoringOutcome::category)
        .containsExactly(MonitoringOutcomeCategory.TRANSIENT_RECOVERY_FAILURE);
    assertThat(weeklyCalls).hasValue(1);
    verify(sessions).recover(1);
    verify(sessions).loadCurrent(1);
    verify(sessions, never()).replace(anyLong(), any());
    verify(sessions, never()).markReconnectRequired(anyLong());
    verify(tracker, never()).reconcileSuccessfulSnapshot(any(), any());
  }

  @Test
  void transientRecoveryBlocksOnlyThatAccountAndMayTryOnceAgainNextCycle() {
    VulcanSessionManager sessions = mock(VulcanSessionManager.class);
    VulcanSession accountA = session("account-a", "sid=a");
    VulcanSession accountB = session("account-b", "sid=b");
    when(sessions.loadCurrent(anyLong()))
        .thenAnswer(
            invocation -> invocation.getArgument(0, Long.class) == 1L ? accountA : accountB);
    when(sessions.recover(1)).thenReturn(VulcanSessionManager.RecoveryResult.TRANSIENT_FAILURE);
    Map<Long, AtomicInteger> weeklyCalls = new HashMap<>();
    RecoveringAccountWeeklyScheduleSource source =
        composition(
            sessions,
            (session, journalId, weekStart) -> {
              weeklyCalls
                  .computeIfAbsent(journalId, ignored -> new AtomicInteger())
                  .incrementAndGet();
              if (session == accountA) {
                throw VulcanHttpException.responseFailure("weekly", 401);
              }
              return snapshot(journalId, weekStart);
            },
            ignored -> {});
    ScheduleChangeTracker tracker = successfulTracker();
    MonitoringCycleRunner runner =
        runner(
            source, tracker, List.of(target(1, 101, 77), target(1, 102, 78), target(2, 201, 79)));

    MonitoringCycleSummary first = runner.runCycle();
    MonitoringCycleSummary second = runner.runCycle();

    assertThat(first.stoppedEarly()).isFalse();
    assertThat(second.stoppedEarly()).isFalse();
    assertThat(first.outcomes())
        .extracting(ScopeMonitoringOutcome::category)
        .containsExactly(
            MonitoringOutcomeCategory.TRANSIENT_RECOVERY_FAILURE,
            MonitoringOutcomeCategory.SUCCESS,
            MonitoringOutcomeCategory.SUCCESS);
    assertThat(second.outcomes())
        .extracting(ScopeMonitoringOutcome::category)
        .containsExactlyElementsOf(
            first.outcomes().stream().map(ScopeMonitoringOutcome::category).toList());
    assertThat(weeklyCalls.get(77L)).hasValue(2);
    assertThat(weeklyCalls).doesNotContainKey(78L);
    assertThat(weeklyCalls.get(79L)).hasValue(4);
    verify(sessions, times(2)).recover(1);
    verify(sessions, never()).markReconnectRequired(1);
    ArgumentCaptor<TrackingScope> tracked = ArgumentCaptor.forClass(TrackingScope.class);
    verify(tracker, times(4)).reconcileSuccessfulSnapshot(tracked.capture(), any());
    assertThat(tracked.getAllValues()).extracting(TrackingScope::vulcanAccountId).containsOnly(2L);
  }

  @Test
  void successfulRecoveryRetriesOncePersistsTheRecoveredSessionAndTracksOnce() {
    VulcanSessionManager sessions = mock(VulcanSessionManager.class);
    VulcanSession expired = session("expired", "sid=old");
    VulcanSession recovered = session("recovered", "sid=new");
    TrackingScope scope = scope(1, 101, 77);
    when(sessions.loadCurrent(1)).thenReturn(expired, recovered);
    when(sessions.recover(1)).thenReturn(VulcanSessionManager.RecoveryResult.RECOVERED);
    AtomicInteger weeklyCalls = new AtomicInteger();
    RecoveringAccountWeeklyScheduleSource source =
        composition(
            sessions,
            (activeSession, journalId, weekStart) -> {
              if (weeklyCalls.getAndIncrement() == 0) {
                assertThat(activeSession).isSameAs(expired);
                throw VulcanHttpException.responseFailure("weekly", 401);
              }
              assertThat(activeSession).isSameAs(recovered);
              return snapshot(journalId, weekStart);
            },
            ignored -> {});
    ScheduleChangeTracker tracker = successfulTracker();
    var coordinator = new ScheduleRefreshCoordinator(source::fetchCompleteWeeklySnapshot, tracker);

    coordinator.refreshSuccessfulWeek(scope);

    assertThat(weeklyCalls).hasValue(2);
    verify(sessions).recover(1);
    verify(sessions).replace(1, recovered);
    verify(sessions, never()).replace(1, expired);
    verify(sessions, never()).markReconnectRequired(1);
    verify(tracker).reconcileSuccessfulSnapshot(scope, snapshot(scope));
  }

  @Test
  void secondAuthenticationFailureMarksReconnectAndStillLetsAnotherAccountRun() {
    VulcanSessionManager sessions = mock(VulcanSessionManager.class);
    VulcanSession expired = session("expired", "sid=old");
    VulcanSession recovered = session("recovered", "sid=new");
    VulcanSession accountB = session("account-b", "sid=b");
    when(sessions.loadCurrent(1)).thenReturn(expired, recovered);
    when(sessions.loadCurrent(2)).thenReturn(accountB);
    when(sessions.recover(1)).thenReturn(VulcanSessionManager.RecoveryResult.RECOVERED);
    Map<Long, AtomicInteger> weeklyCalls = new HashMap<>();
    RecoveringAccountWeeklyScheduleSource source =
        composition(
            sessions,
            (activeSession, journalId, weekStart) -> {
              weeklyCalls
                  .computeIfAbsent(journalId, ignored -> new AtomicInteger())
                  .incrementAndGet();
              if (activeSession != accountB) {
                throw VulcanHttpException.responseFailure("weekly", 401);
              }
              return snapshot(journalId, weekStart);
            },
            ignored -> {});
    ScheduleChangeTracker tracker = successfulTracker();

    MonitoringCycleSummary summary =
        runner(source, tracker, List.of(target(1, 101, 77), target(1, 102, 78), target(2, 201, 79)))
            .runCycle();

    assertThat(summary.stoppedEarly()).isFalse();
    assertThat(summary.outcomes())
        .extracting(ScopeMonitoringOutcome::category)
        .containsExactly(
            MonitoringOutcomeCategory.AUTHENTICATION_REQUIRED,
            MonitoringOutcomeCategory.SUCCESS,
            MonitoringOutcomeCategory.SUCCESS);
    assertThat(weeklyCalls.get(77L)).hasValue(2);
    assertThat(weeklyCalls).doesNotContainKey(78L);
    assertThat(weeklyCalls.get(79L)).hasValue(2);
    verify(sessions).recover(1);
    verify(sessions).markReconnectRequired(1);
    ArgumentCaptor<TrackingScope> tracked = ArgumentCaptor.forClass(TrackingScope.class);
    verify(tracker, times(2)).reconcileSuccessfulSnapshot(tracked.capture(), any());
    assertThat(tracked.getAllValues()).extracting(TrackingScope::vulcanAccountId).containsOnly(2L);
  }

  @Test
  void ordinaryTransportFailuresStillUseTheBoundedRetryPolicy() {
    VulcanSessionManager sessions = mock(VulcanSessionManager.class);
    VulcanSession current = session("current", "sid=current");
    TrackingScope scope = scope(1, 101, 77);
    when(sessions.loadCurrent(1)).thenReturn(current);
    AtomicInteger weeklyCalls = new AtomicInteger();
    RecordingDelay delays = new RecordingDelay();
    RecoveringAccountWeeklyScheduleSource source =
        composition(
            sessions,
            (ignored, journalId, weekStart) -> {
              if (weeklyCalls.incrementAndGet() < 3) {
                throw VulcanHttpException.transportFailure("weekly");
              }
              return snapshot(journalId, weekStart);
            },
            delays);

    assertThat(source.fetchCompleteWeeklySnapshot(scope)).isEqualTo(snapshot(scope));

    assertThat(weeklyCalls).hasValue(3);
    assertThat(delays.values).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
    verify(sessions, never()).recover(anyLong());
    verify(sessions).replace(1, current);
  }

  @Test
  void recoveredRateLimitRemainsAccountScopedWithoutAnotherRecovery() {
    VulcanSessionManager sessions = mock(VulcanSessionManager.class);
    VulcanSession expired = session("expired", "sid=old");
    VulcanSession recovered = session("recovered", "sid=new");
    VulcanSession accountB = session("account-b", "sid=b");
    TrackingScope accountA = scope(1, 101, 77);
    TrackingScope scopeB = scope(2, 201, 77);
    when(sessions.loadCurrent(1)).thenReturn(expired, recovered);
    when(sessions.loadCurrent(2)).thenReturn(accountB);
    when(sessions.recover(1)).thenReturn(VulcanSessionManager.RecoveryResult.RECOVERED);
    AtomicInteger weeklyCalls = new AtomicInteger();
    RecoveringAccountWeeklyScheduleSource source =
        composition(
            sessions,
            (activeSession, journalId, weekStart) -> {
              weeklyCalls.incrementAndGet();
              if (activeSession == expired) {
                throw VulcanHttpException.responseFailure("weekly", 401);
              }
              if (activeSession == recovered) {
                throw VulcanHttpException.responseFailure("weekly", 429, Duration.ofSeconds(30));
              }
              return snapshot(journalId, weekStart);
            },
            ignored -> {});

    assertThatThrownBy(() -> source.fetchCompleteWeeklySnapshot(accountA))
        .isInstanceOfSatisfying(
            ScheduleSourceException.class,
            failure -> assertThat(failure.kind()).isEqualTo(SourceFailureKind.DEFERRED_RATE_LIMIT));
    assertThat(source.fetchCompleteWeeklySnapshot(scopeB)).isEqualTo(snapshot(scopeB));

    assertThat(weeklyCalls).hasValue(3);
    verify(sessions).recover(1);
    verify(sessions, never()).recover(2);
  }

  private static RecoveringAccountWeeklyScheduleSource composition(
      VulcanSessionManager sessions, SessionWeeklyScheduleFetcher fetcher, DelayStrategy delay) {
    var persisted = new PersistedAccountWeeklyScheduleSource(sessions, fetcher);
    var resilient =
        new ResilientWeeklyScheduleSource(
            persisted,
            delay,
            new RateLimitBackoffGate(CLOCK),
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10));
    return new RecoveringAccountWeeklyScheduleSource(resilient, sessions);
  }

  private static MonitoringCycleRunner runner(
      RecoveringAccountWeeklyScheduleSource source,
      ScheduleChangeTracker tracker,
      List<MonitoringTarget> targets) {
    return new MonitoringCycleRunner(
        () -> targets,
        new MonitoringScopePlanner(CLOCK),
        new ScheduleRefreshCoordinator(source::fetchCompleteWeeklySnapshot, tracker),
        ignored -> {},
        Duration.ZERO,
        CLOCK);
  }

  private static ScheduleChangeTracker successfulTracker() {
    ScheduleChangeTracker tracker = mock(ScheduleChangeTracker.class);
    when(tracker.reconcileSuccessfulSnapshot(any(), any()))
        .thenReturn(new TrackingResult(false, 0, List.of()));
    return tracker;
  }

  private static MonitoringTarget target(long accountId, long catalogId, long journalId) {
    return new MonitoringTarget(accountId, catalogId, journalId);
  }

  private static TrackingScope scope(long accountId, long catalogId, long journalId) {
    return new TrackingScope(accountId, catalogId, journalId, WEEK_START, WEEK_START.plusDays(6));
  }

  private static ScheduleSnapshot snapshot(TrackingScope scope) {
    return snapshot(scope.journalId(), scope.weekStart());
  }

  private static ScheduleSnapshot snapshot(long journalId, LocalDate weekStart) {
    return new ScheduleSnapshot(journalId, weekStart, weekStart.plusDays(6), List.of(), List.of());
  }

  private static VulcanSession session(String path, String cookie) {
    return VulcanSession.fromBrowserSession(
        URI.create("https://synthetic.invalid/" + path + "/"), "token", "guid", cookie);
  }

  private static final class RecordingDelay implements DelayStrategy {

    private final List<Duration> values = new ArrayList<>();

    @Override
    public void delay(Duration duration) {
      values.add(duration);
    }
  }
}
