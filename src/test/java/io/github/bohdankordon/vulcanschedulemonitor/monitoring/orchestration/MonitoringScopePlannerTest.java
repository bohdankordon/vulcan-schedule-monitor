package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonitoringScopePlannerTest {

  @Test
  void mondayPlansCurrentAndSeparateNextWeek() {
    List<TrackingScope> scopes = planAt("2026-09-07T10:00:00Z", new MonitoringTarget(42));

    assertThat(scopes)
        .extracting(TrackingScope::weekStart)
        .containsExactly(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14));
    assertThat(scopes)
        .extracting(TrackingScope::weekEnd)
        .containsExactly(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 20));
  }

  @Test
  void sundayRemainsInCurrentPolishWeek() {
    assertThat(planAt("2026-09-13T18:00:00Z", new MonitoringTarget(42)))
        .extracting(TrackingScope::weekStart)
        .containsExactly(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14));
  }

  @Test
  void warsawDateWinsAtUtcLocalDayBoundary() {
    assertThat(planAt("2026-09-06T22:30:00Z", new MonitoringTarget(42)))
        .extracting(TrackingScope::weekStart)
        .containsExactly(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14));
  }

  @Test
  void yearBoundaryProducesValidMondaySundayScopes() {
    assertThat(planAt("2026-12-31T12:00:00Z", new MonitoringTarget(42)))
        .extracting(TrackingScope::weekStart, TrackingScope::weekEnd)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)),
            org.assertj.core.groups.Tuple.tuple(
                LocalDate.of(2027, 1, 4), LocalDate.of(2027, 1, 10)));
  }

  @Test
  void targetsAreDeduplicatedSortedAndEachProducesExactlyTwoScopes() {
    List<TrackingScope> scopes =
        planAt(
            "2026-09-09T10:00:00Z",
            new MonitoringTarget(9),
            new MonitoringTarget(2),
            new MonitoringTarget(9));

    assertThat(scopes).hasSize(4);
    assertThat(scopes).extracting(TrackingScope::journalId).containsExactly(2L, 2L, 9L, 9L);
    assertThat(scopes)
        .extracting(TrackingScope::weekStart)
        .containsExactly(
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 9, 14),
            LocalDate.of(2026, 9, 7),
            LocalDate.of(2026, 9, 14));
  }

  private static List<TrackingScope> planAt(String instant, MonitoringTarget... targets) {
    Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    return new MonitoringScopePlanner(clock).plan(List.of(targets));
  }
}
