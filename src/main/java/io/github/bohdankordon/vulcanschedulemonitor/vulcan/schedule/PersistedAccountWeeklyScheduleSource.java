package io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionManager;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.util.Objects;

/** Loads and rotates the encrypted session belonging to each requested monitoring scope. */
public final class PersistedAccountWeeklyScheduleSource implements WeeklyScheduleSource {

  private static final String OPERATION = "GetPlanLekcjiContext";

  private final VulcanSessionManager sessions;
  private final SessionWeeklyScheduleFetcher fetcher;

  public PersistedAccountWeeklyScheduleSource(VulcanSessionManager sessions) {
    this(
        sessions,
        (session, journalId, weekStart) ->
            new VulcanClient(session).getWeekSchedule(journalId, weekStart));
  }

  public PersistedAccountWeeklyScheduleSource(
      VulcanSessionManager sessions, SessionWeeklyScheduleFetcher fetcher) {
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
    this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
  }

  @Override
  public ScheduleSnapshot fetchCompleteWeeklySnapshot(TrackingScope scope) {
    Objects.requireNonNull(scope, "scope must not be null");
    VulcanSession session = sessions.loadCurrent(scope.vulcanAccountId());
    try {
      return fetchAndPersist(scope, session);
    } catch (VulcanHttpException failure) {
      if (!requiresAuthentication(failure.category())) {
        throw failure;
      }
      return recoverAndRetryOnce(scope, failure);
    }
  }

  private ScheduleSnapshot recoverAndRetryOnce(TrackingScope scope, VulcanHttpException original) {
    VulcanSessionManager.RecoveryResult result = sessions.recover(scope.vulcanAccountId());
    if (result == VulcanSessionManager.RecoveryResult.RECONNECT_REQUIRED) {
      throw original;
    }
    if (result == VulcanSessionManager.RecoveryResult.TRANSIENT_FAILURE) {
      throw VulcanHttpException.transportFailure(OPERATION);
    }

    VulcanSession recovered = sessions.loadCurrent(scope.vulcanAccountId());
    try {
      return fetchAndPersist(scope, recovered);
    } catch (VulcanHttpException retryFailure) {
      if (requiresAuthentication(retryFailure.category())) {
        sessions.markReconnectRequired(scope.vulcanAccountId());
      }
      throw retryFailure;
    }
  }

  private ScheduleSnapshot fetchAndPersist(TrackingScope scope, VulcanSession session) {
    ScheduleSnapshot snapshot =
        Objects.requireNonNull(
            fetcher.fetch(session, scope.journalId(), scope.weekStart()),
            "VULCAN schedule fetcher must return a complete snapshot or throw");
    if (!scope.matches(snapshot)) {
      throw new VulcanProtocolException(OPERATION);
    }
    sessions.replace(scope.vulcanAccountId(), session);
    return snapshot;
  }

  private static boolean requiresAuthentication(VulcanFailureCategory category) {
    return category == VulcanFailureCategory.AUTHENTICATION_REQUIRED
        || category == VulcanFailureCategory.SESSION_REDIRECT
        || category == VulcanFailureCategory.UNEXPECTED_HTML;
  }
}
