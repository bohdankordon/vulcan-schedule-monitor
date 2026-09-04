package io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import java.util.Objects;

/** Adapts an explicitly authorized VULCAN client to the weekly schedule source port. */
public final class VulcanWeeklyScheduleSource implements WeeklyScheduleSource {

  private static final String OPERATION = "GetPlanLekcjiContext";

  private final VulcanClient client;

  public VulcanWeeklyScheduleSource(VulcanClient client) {
    this.client = Objects.requireNonNull(client, "client must not be null");
  }

  @Override
  public ScheduleSnapshot fetchCompleteWeeklySnapshot(TrackingScope scope) {
    Objects.requireNonNull(scope, "scope must not be null");
    ScheduleSnapshot snapshot = client.getWeekSchedule(scope.journalId(), scope.weekStart());
    if (!scope.matches(snapshot)) {
      throw new VulcanProtocolException(OPERATION);
    }
    return snapshot;
  }
}
