package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ScheduleRefreshCoordinator;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingResult;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sequential application service for one bounded monitoring cycle. */
public final class MonitoringCycleRunner {

  private static final Logger LOGGER = LoggerFactory.getLogger(MonitoringCycleRunner.class);

  private final MonitoringTargetProvider targetProvider;
  private final MonitoringScopePlanner scopePlanner;
  private final ScheduleRefreshCoordinator coordinator;
  private final DelayStrategy delayStrategy;
  private final Duration requestSpacing;
  private final Clock clock;

  public MonitoringCycleRunner(
      MonitoringTargetProvider targetProvider,
      MonitoringScopePlanner scopePlanner,
      ScheduleRefreshCoordinator coordinator,
      DelayStrategy delayStrategy,
      Duration requestSpacing,
      Clock clock) {
    this.targetProvider = Objects.requireNonNull(targetProvider, "targetProvider must not be null");
    this.scopePlanner = Objects.requireNonNull(scopePlanner, "scopePlanner must not be null");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
    this.delayStrategy = Objects.requireNonNull(delayStrategy, "delayStrategy must not be null");
    this.requestSpacing = Objects.requireNonNull(requestSpacing, "requestSpacing must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    if (requestSpacing.isNegative()) {
      throw new IllegalArgumentException("requestSpacing must not be negative");
    }
  }

  public MonitoringCycleSummary runCycle() {
    Instant startedAt = clock.instant();
    Collection<MonitoringTarget> providedTargets =
        Objects.requireNonNull(
            targetProvider.activeTargets(), "target provider must not return null");
    TreeSet<MonitoringTarget> targets = new TreeSet<>(providedTargets);
    List<TrackingScope> scopes = scopePlanner.plan(targets);
    List<ScopeMonitoringOutcome> outcomes = new ArrayList<>();
    boolean stoppedEarly = false;
    LOGGER.info("Monitoring cycle started: targets={}, scopes={}", targets.size(), scopes.size());

    for (int index = 0; index < scopes.size(); index++) {
      TrackingScope scope = scopes.get(index);
      ScopeMonitoringOutcome outcome = refresh(scope);
      outcomes.add(outcome);
      if (mustStop(outcome.category())) {
        stoppedEarly = index + 1 < scopes.size();
        break;
      }
      if (index + 1 < scopes.size() && !pace()) {
        stoppedEarly = true;
        break;
      }
    }

    MonitoringCycleSummary summary =
        new MonitoringCycleSummary(
            startedAt, clock.instant(), targets.size(), scopes.size(), stoppedEarly, outcomes);
    LOGGER.info(
        "Monitoring cycle completed: successes={}, failures={}, stoppedEarly={}",
        summary.successCount(),
        summary.failureCount(),
        summary.stoppedEarly());
    return summary;
  }

  private ScopeMonitoringOutcome refresh(TrackingScope scope) {
    try {
      TrackingResult result = coordinator.refreshSuccessfulWeek(scope);
      MonitoringOutcomeCategory category = MonitoringOutcomeCategory.SUCCESS;
      if (result.baselineEstablishedNow()) {
        category = MonitoringOutcomeCategory.BASELINE_ESTABLISHED;
      } else if (!result.transitions().isEmpty()) {
        category = MonitoringOutcomeCategory.TRANSITIONS;
      }
      return ScopeMonitoringOutcome.success(
          scope, category, result.activeChangeCount(), result.transitions().size());
    } catch (ScheduleSourceException exception) {
      MonitoringOutcomeCategory category = map(exception.kind());
      LOGGER.warn(
          "Weekly monitoring scope failed: weekStart={}, category={}", scope.weekStart(), category);
      return ScopeMonitoringOutcome.failure(
          scope, category, exception.deferredUntil().orElse(null));
    } catch (RuntimeException exception) {
      LOGGER.error("Weekly monitoring scope failed unexpectedly: weekStart={}", scope.weekStart());
      return ScopeMonitoringOutcome.failure(
          scope, MonitoringOutcomeCategory.PERMANENT_FAILURE, null);
    }
  }

  private boolean pace() {
    try {
      delayStrategy.delay(requestSpacing);
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static boolean mustStop(MonitoringOutcomeCategory category) {
    return category == MonitoringOutcomeCategory.AUTHENTICATION_REQUIRED
        || category == MonitoringOutcomeCategory.DEFERRED_RATE_LIMIT;
  }

  private static MonitoringOutcomeCategory map(SourceFailureKind kind) {
    return switch (kind) {
      case AUTHENTICATION_REQUIRED -> MonitoringOutcomeCategory.AUTHENTICATION_REQUIRED;
      case DEFERRED_RATE_LIMIT -> MonitoringOutcomeCategory.DEFERRED_RATE_LIMIT;
      case TRANSIENT_FAILURE_EXHAUSTED, INTERRUPTED ->
          MonitoringOutcomeCategory.TRANSIENT_FAILURE_EXHAUSTED;
      case PERMANENT_FAILURE -> MonitoringOutcomeCategory.PERMANENT_FAILURE;
      case PROTOCOL_FAILURE -> MonitoringOutcomeCategory.PROTOCOL_FAILURE;
    };
  }
}
