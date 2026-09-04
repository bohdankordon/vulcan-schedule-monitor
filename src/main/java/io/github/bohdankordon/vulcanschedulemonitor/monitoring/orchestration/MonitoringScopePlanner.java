package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

public final class MonitoringScopePlanner {

  public static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

  private final Clock clock;

  public MonitoringScopePlanner(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public List<TrackingScope> plan(Collection<MonitoringTarget> targets) {
    Objects.requireNonNull(targets, "targets must not be null");
    LocalDate weekStart =
        LocalDate.now(clock.withZone(WARSAW))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    List<TrackingScope> scopes = new ArrayList<>();
    for (MonitoringTarget target : new TreeSet<>(targets)) {
      scopes.add(new TrackingScope(target.journalId(), weekStart, weekStart.plusDays(6)));
      LocalDate nextWeekStart = weekStart.plusWeeks(1);
      scopes.add(new TrackingScope(target.journalId(), nextWeekStart, nextWeekStart.plusDays(6)));
    }
    return List.copyOf(scopes);
  }
}
