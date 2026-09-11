package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitResponseObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded retry and rate-limit policy around a weekly schedule source. */
public final class ResilientWeeklyScheduleSource {

  private static final Logger LOGGER = LoggerFactory.getLogger(ResilientWeeklyScheduleSource.class);

  private final WeeklyScheduleSource delegate;
  private final DelayStrategy delayStrategy;
  private final RateLimitBackoffGate gate;
  private final int maxAttempts;
  private final Duration initialBackoff;
  private final Duration fallbackRateLimitDelay;
  private final Duration maximumInlineRateLimitDelay;

  public ResilientWeeklyScheduleSource(
      WeeklyScheduleSource delegate,
      DelayStrategy delayStrategy,
      RateLimitBackoffGate gate,
      int maxAttempts,
      Duration initialBackoff,
      Duration fallbackRateLimitDelay,
      Duration maximumInlineRateLimitDelay) {
    this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    this.delayStrategy = Objects.requireNonNull(delayStrategy, "delayStrategy must not be null");
    this.gate = Objects.requireNonNull(gate, "gate must not be null");
    if (maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalArgumentException("maxAttempts must be between 1 and 10");
    }
    this.maxAttempts = maxAttempts;
    this.initialBackoff = requireNonNegative(initialBackoff, "initialBackoff");
    this.fallbackRateLimitDelay =
        requireNonNegative(fallbackRateLimitDelay, "fallbackRateLimitDelay");
    this.maximumInlineRateLimitDelay =
        requireNonNegative(maximumInlineRateLimitDelay, "maximumInlineRateLimitDelay");
  }

  public ScheduleSnapshot fetchCompleteWeeklySnapshot(TrackingScope scope) {
    Objects.requireNonNull(scope, "scope must not be null");
    gate.activeUntil(scope.vulcanAccountId())
        .ifPresent(
            until -> {
              throw ScheduleSourceException.deferred(until);
            });

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return Objects.requireNonNull(
            delegate.fetchCompleteWeeklySnapshot(scope),
            "schedule source must return a complete snapshot or throw");
      } catch (VulcanProtocolException exception) {
        throw ScheduleSourceException.of(SourceFailureKind.PROTOCOL_FAILURE);
      } catch (VulcanHttpException exception) {
        VulcanFailureCategory category = exception.category();
        if (category == VulcanFailureCategory.AUTHENTICATION_REQUIRED
            || category == VulcanFailureCategory.SESSION_REDIRECT
            || category == VulcanFailureCategory.UNEXPECTED_HTML) {
          throw ScheduleSourceException.of(SourceFailureKind.AUTHENTICATION_REQUIRED);
        }
        if (category == VulcanFailureCategory.PERMANENT_HTTP) {
          throw ScheduleSourceException.of(SourceFailureKind.PERMANENT_FAILURE);
        }
        if (category == VulcanFailureCategory.RATE_LIMITED) {
          RateLimitResponseObservation observation = exception.rateLimitObservation().orElse(null);
          if (Schedule429Classifier.classify(observation) == Schedule429Disposition.STALE_SESSION) {
            LOGGER.warn(
                VulcanRateLimitLogFormatter.format(
                    observation,
                    exception.operation(),
                    DelaySource.NONE,
                    EffectiveDelayBucket.ZERO,
                    ResilienceDecision.AUTHENTICATION_REQUIRED,
                    attempt,
                    maxAttempts));
            throw ScheduleSourceException.of(SourceFailureKind.AUTHENTICATION_REQUIRED);
          }
          Duration requiredDelay = exception.retryAfter().orElse(fallbackRateLimitDelay);
          DelaySource delaySource =
              exception.retryAfter().isPresent() ? DelaySource.HEADER : DelaySource.FALLBACK;
          EffectiveDelayBucket delayBucket = EffectiveDelayBucket.fromDuration(requiredDelay);
          ResilienceDecision decision =
              (attempt == maxAttempts || requiredDelay.compareTo(maximumInlineRateLimitDelay) > 0)
                  ? ResilienceDecision.DEFERRED_GATE
                  : ResilienceDecision.INLINE_RETRY;
          LOGGER.warn(
              VulcanRateLimitLogFormatter.format(
                  observation,
                  exception.operation(),
                  delaySource,
                  delayBucket,
                  decision,
                  attempt,
                  maxAttempts));
          Instant deferredUntil = gate.extend(scope.vulcanAccountId(), requiredDelay);
          if (attempt == maxAttempts || requiredDelay.compareTo(maximumInlineRateLimitDelay) > 0) {
            throw ScheduleSourceException.deferred(deferredUntil);
          }
          waitFor(requiredDelay);
          gate.release(scope.vulcanAccountId(), deferredUntil);
          continue;
        }
        if (attempt == maxAttempts) {
          throw ScheduleSourceException.of(SourceFailureKind.TRANSIENT_FAILURE_EXHAUSTED);
        }
        Duration backoff = initialBackoff.multipliedBy(1L << (attempt - 1));
        Duration retryAfter = exception.retryAfter().orElse(Duration.ZERO);
        waitFor(backoff.compareTo(retryAfter) >= 0 ? backoff : retryAfter);
      }
    }
    throw new IllegalStateException("unreachable retry state");
  }

  private void waitFor(Duration delay) {
    try {
      delayStrategy.delay(delay);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw ScheduleSourceException.of(SourceFailureKind.INTERRUPTED);
    }
  }

  private static Duration requireNonNegative(Duration duration, String name) {
    Objects.requireNonNull(duration, name + " must not be null");
    if (duration.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return duration;
  }
}
