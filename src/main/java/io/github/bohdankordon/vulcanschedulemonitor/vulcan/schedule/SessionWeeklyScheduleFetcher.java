package io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.time.LocalDate;

@FunctionalInterface
public interface SessionWeeklyScheduleFetcher {

  ScheduleSnapshot fetch(VulcanSession session, long journalId, LocalDate weekStart);
}
