package io.github.bohdankordon.vulcanschedulemonitor.telegram.availability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class TelegramProviderAvailabilityGate {

  private final Clock clock;
  private Instant deferredUntil;
  private boolean suspended;

  public TelegramProviderAvailabilityGate(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public synchronized TelegramProviderAvailability availability() {
    if (suspended) {
      return TelegramProviderAvailability.SUSPENDED_UNTIL_RESTART;
    }
    if (deferredUntil != null && clock.instant().isBefore(deferredUntil)) {
      return TelegramProviderAvailability.DEFERRED;
    }
    deferredUntil = null;
    return TelegramProviderAvailability.AVAILABLE;
  }

  public synchronized boolean isAvailable() {
    return availability() == TelegramProviderAvailability.AVAILABLE;
  }

  public synchronized void defer(Duration delay) {
    Objects.requireNonNull(delay, "delay must not be null");
    if (delay.isNegative() || delay.isZero() || suspended) {
      return;
    }
    Instant candidate = clock.instant().plus(delay);
    if (deferredUntil == null || candidate.isAfter(deferredUntil)) {
      deferredUntil = candidate;
    }
  }

  public synchronized void suspendUntilRestart() {
    suspended = true;
    deferredUntil = null;
  }
}
