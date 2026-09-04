package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Process-local account/source backoff state. */
public final class RateLimitBackoffGate {

  private final Clock clock;
  private final ConcurrentMap<Long, Instant> notBeforeByAccount = new ConcurrentHashMap<>();

  public RateLimitBackoffGate(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public Optional<Instant> activeUntil(long vulcanAccountId) {
    requireAccountId(vulcanAccountId);
    Instant value = notBeforeByAccount.getOrDefault(vulcanAccountId, Instant.MIN);
    if (!clock.instant().isBefore(value)) {
      notBeforeByAccount.remove(vulcanAccountId, value);
      return Optional.empty();
    }
    return clock.instant().isBefore(value) ? Optional.of(value) : Optional.empty();
  }

  public Instant extend(long vulcanAccountId, Duration delay) {
    requireAccountId(vulcanAccountId);
    Objects.requireNonNull(delay, "delay must not be null");
    Instant candidate = safePlus(clock.instant(), delay);
    return notBeforeByAccount.merge(
        vulcanAccountId,
        candidate,
        (existing, update) -> existing.isAfter(update) ? existing : update);
  }

  public void release(long vulcanAccountId, Instant expected) {
    requireAccountId(vulcanAccountId);
    notBeforeByAccount.remove(vulcanAccountId, expected);
  }

  private static void requireAccountId(long vulcanAccountId) {
    if (vulcanAccountId <= 0) {
      throw new IllegalArgumentException("VULCAN account id must be positive");
    }
  }

  private static Instant safePlus(Instant start, Duration delay) {
    try {
      return start.plus(delay);
    } catch (DateTimeException | ArithmeticException exception) {
      return Instant.MAX;
    }
  }
}
