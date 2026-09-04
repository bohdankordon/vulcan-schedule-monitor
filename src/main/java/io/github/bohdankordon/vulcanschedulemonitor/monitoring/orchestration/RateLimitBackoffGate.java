package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local account/source backoff state. */
public final class RateLimitBackoffGate {

  private final Clock clock;
  private final AtomicReference<Instant> notBefore = new AtomicReference<>(Instant.MIN);

  public RateLimitBackoffGate(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public Optional<Instant> activeUntil() {
    Instant value = notBefore.get();
    return clock.instant().isBefore(value) ? Optional.of(value) : Optional.empty();
  }

  public Instant extend(Duration delay) {
    Objects.requireNonNull(delay, "delay must not be null");
    Instant candidate = safePlus(clock.instant(), delay);
    return notBefore.updateAndGet(existing -> existing.isAfter(candidate) ? existing : candidate);
  }

  public void release(Instant expected) {
    notBefore.compareAndSet(expected, Instant.MIN);
  }

  private static Instant safePlus(Instant start, Duration delay) {
    try {
      return start.plus(delay);
    } catch (DateTimeException | ArithmeticException exception) {
      return Instant.MAX;
    }
  }
}
