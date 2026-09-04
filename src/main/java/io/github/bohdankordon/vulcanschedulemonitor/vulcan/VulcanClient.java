package io.github.bohdankordon.vulcanschedulemonitor.vulcan;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap.SchoolBootstrap;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap.VulcanBootstrapAdapter;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.VulcanJournalAdapter;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule.VulcanScheduleAdapter;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/** Read-only entry point for the currently supported VULCAN protocol operations. */
public final class VulcanClient {

  private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);

  private final VulcanBootstrapAdapter bootstrapAdapter;
  private final VulcanJournalAdapter journalAdapter;
  private final VulcanScheduleAdapter scheduleAdapter;

  public VulcanClient(VulcanSession session) {
    this(session, Clock.system(WARSAW));
  }

  public VulcanClient(VulcanSession session, Clock clock) {
    Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(clock, "clock must not be null");
    VulcanHttpTransport transport =
        new VulcanHttpTransport(session, CONNECT_TIMEOUT, READ_TIMEOUT, clock);
    this.bootstrapAdapter = new VulcanBootstrapAdapter(session, transport, clock);
    this.journalAdapter = new VulcanJournalAdapter(session, transport, clock);
    this.scheduleAdapter = new VulcanScheduleAdapter(session, transport);
  }

  public SchoolBootstrap getCache() {
    return bootstrapAdapter.getCache();
  }

  public List<SchoolClass> getTree(int schoolYear) {
    return journalAdapter.getTree(schoolYear);
  }

  public ScheduleSnapshot getWeekSchedule(long journalId, LocalDate dateWithinWeek) {
    return scheduleAdapter.getWeekSchedule(journalId, dateWithinWeek);
  }
}
