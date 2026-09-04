package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionManager;
import java.util.Objects;

/** Adds one account recovery budget around ordinary weekly-request resilience. */
public final class RecoveringAccountWeeklyScheduleSource {

  private final ResilientWeeklyScheduleSource source;
  private final VulcanSessionManager sessions;

  public RecoveringAccountWeeklyScheduleSource(
      ResilientWeeklyScheduleSource source, VulcanSessionManager sessions) {
    this.source = Objects.requireNonNull(source, "source must not be null");
    this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
  }

  public ScheduleSnapshot fetchCompleteWeeklySnapshot(TrackingScope scope) {
    Objects.requireNonNull(scope, "scope must not be null");
    try {
      return source.fetchCompleteWeeklySnapshot(scope);
    } catch (ScheduleSourceException failure) {
      if (failure.kind() != SourceFailureKind.AUTHENTICATION_REQUIRED) {
        throw failure;
      }
      return recoverOnce(scope, failure);
    }
  }

  private ScheduleSnapshot recoverOnce(TrackingScope scope, ScheduleSourceException original) {
    return switch (sessions.recover(scope.vulcanAccountId())) {
      case RECOVERED -> retryWithRecoveredSession(scope);
      case TRANSIENT_FAILURE ->
          throw ScheduleSourceException.of(SourceFailureKind.TRANSIENT_RECOVERY_FAILURE);
      case RECONNECT_REQUIRED -> throw original;
    };
  }

  private ScheduleSnapshot retryWithRecoveredSession(TrackingScope scope) {
    try {
      return source.fetchCompleteWeeklySnapshot(scope);
    } catch (ScheduleSourceException failure) {
      if (failure.kind() == SourceFailureKind.AUTHENTICATION_REQUIRED) {
        sessions.markReconnectRequired(scope.vulcanAccountId());
      }
      throw failure;
    }
  }
}
