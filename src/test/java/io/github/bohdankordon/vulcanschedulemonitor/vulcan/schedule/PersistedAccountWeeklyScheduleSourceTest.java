package io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleChangeTracker;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleRefreshCoordinator;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionManager;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.SecretDecryptionException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PersistedAccountWeeklyScheduleSourceTest {

  private static final LocalDate WEEK_START = LocalDate.of(2026, 9, 7);

  private final VulcanSessionManager sessions = mock(VulcanSessionManager.class);

  @Test
  void sameProtocolJournalUsesTheSessionOwnedByEachRequestedAccount() {
    VulcanSession accountA = session("account-a", "sid=a");
    VulcanSession accountB = session("account-b", "sid=b");
    TrackingScope scopeA = scope(11, 101, 77);
    TrackingScope scopeB = scope(22, 202, 77);
    when(sessions.loadCurrent(11)).thenReturn(accountA);
    when(sessions.loadCurrent(22)).thenReturn(accountB);
    SessionWeeklyScheduleFetcher fetcher =
        (session, journalId, weekStart) -> {
          assertThat(session).isIn(accountA, accountB);
          if (session == accountA) {
            assertThat(journalId).isEqualTo(scopeA.journalId());
            return snapshot(scopeA);
          }
          assertThat(journalId).isEqualTo(scopeB.journalId());
          return snapshot(scopeB);
        };
    var source = new PersistedAccountWeeklyScheduleSource(sessions, fetcher);

    assertThat(source.fetchCompleteWeeklySnapshot(scopeA)).isEqualTo(snapshot(scopeA));
    assertThat(source.fetchCompleteWeeklySnapshot(scopeB)).isEqualTo(snapshot(scopeB));

    verify(sessions).loadCurrent(11);
    verify(sessions).loadCurrent(22);
    verify(sessions).replace(11, accountA);
    verify(sessions).replace(22, accountB);
  }

  @Test
  void authenticationFailureIsLeftForTheOuterRecoveryLayer() {
    TrackingScope scope = scope(11, 101, 77);
    VulcanSession expired = session("expired", "sid=old");
    AtomicInteger calls = new AtomicInteger();
    SessionWeeklyScheduleFetcher fetcher =
        (session, journalId, weekStart) -> {
          calls.incrementAndGet();
          assertThat(session).isSameAs(expired);
          throw VulcanHttpException.responseFailure("weekly", 401);
        };
    when(sessions.loadCurrent(11)).thenReturn(expired);
    var source = new PersistedAccountWeeklyScheduleSource(sessions, fetcher);

    assertThatThrownBy(() -> source.fetchCompleteWeeklySnapshot(scope))
        .isInstanceOf(VulcanHttpException.class);

    assertThat(calls).hasValue(1);
    verify(sessions, never()).recover(11);
    verify(sessions, never()).replace(11, expired);
  }

  @Test
  void failedRotationPersistenceOrSessionDecryptionCannotReachTracker() {
    TrackingScope scope = scope(11, 101, 77);
    VulcanSession current = session("current", "sid=current");
    when(sessions.loadCurrent(11)).thenReturn(current);
    doThrow(new IllegalStateException("synthetic persistence failure"))
        .when(sessions)
        .replace(11, current);
    ScheduleChangeTracker tracker = mock(ScheduleChangeTracker.class);
    var coordinator =
        new ScheduleRefreshCoordinator(
            new PersistedAccountWeeklyScheduleSource(
                sessions, (session, journalId, weekStart) -> snapshot(scope)),
            tracker);

    assertThatThrownBy(() -> coordinator.refreshSuccessfulWeek(scope))
        .isInstanceOf(IllegalStateException.class);
    verify(tracker, never())
        .reconcileSuccessfulSnapshot(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

    VulcanSessionManager unreadableSessions = mock(VulcanSessionManager.class);
    when(unreadableSessions.loadCurrent(11)).thenThrow(new SecretDecryptionException());
    var unreadableCoordinator =
        new ScheduleRefreshCoordinator(
            new PersistedAccountWeeklyScheduleSource(
                unreadableSessions,
                (session, journalId, weekStart) -> {
                  throw new AssertionError("fetch must not run");
                }),
            tracker);
    assertThatThrownBy(() -> unreadableCoordinator.refreshSuccessfulWeek(scope))
        .isInstanceOf(SecretDecryptionException.class);
    verify(tracker, never())
        .reconcileSuccessfulSnapshot(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  private static VulcanSession session(String path, String cookie) {
    return VulcanSession.fromBrowserSession(
        URI.create("https://synthetic.invalid/" + path + "/"), "token", "guid", cookie);
  }

  private static TrackingScope scope(long accountId, long catalogId, long journalId) {
    return new TrackingScope(accountId, catalogId, journalId, WEEK_START, WEEK_START.plusDays(6));
  }

  private static ScheduleSnapshot snapshot(TrackingScope scope) {
    return new ScheduleSnapshot(
        scope.journalId(), scope.weekStart(), scope.weekEnd(), List.of(), List.of());
  }
}
