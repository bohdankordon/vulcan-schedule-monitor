package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.time.Instant;
import java.util.Optional;

/** Protocol-neutral, sanitized schedule-source failure. */
public final class ScheduleSourceException extends RuntimeException {

  private final SourceFailureKind kind;
  private final Instant deferredUntil;

  private ScheduleSourceException(SourceFailureKind kind, Instant deferredUntil) {
    super("Weekly schedule source failed: " + kind);
    this.kind = kind;
    this.deferredUntil = deferredUntil;
  }

  public static ScheduleSourceException of(SourceFailureKind kind) {
    return new ScheduleSourceException(kind, null);
  }

  public static ScheduleSourceException deferred(Instant deferredUntil) {
    return new ScheduleSourceException(SourceFailureKind.DEFERRED_RATE_LIMIT, deferredUntil);
  }

  public SourceFailureKind kind() {
    return kind;
  }

  public Optional<Instant> deferredUntil() {
    return Optional.ofNullable(deferredUntil);
  }
}
