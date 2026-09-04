package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;
import java.util.Objects;
import java.util.Optional;

public record ChangeTransition(
    ChangeLifecycle lifecycle,
    String changeKey,
    ChangeMetadata metadata,
    Optional<ScheduleChange> currentChange) {

  public ChangeTransition {
    Objects.requireNonNull(lifecycle, "lifecycle must not be null");
    Objects.requireNonNull(changeKey, "changeKey must not be null");
    Objects.requireNonNull(metadata, "metadata must not be null");
    currentChange = Objects.requireNonNull(currentChange, "currentChange must not be null");
    if (lifecycle == ChangeLifecycle.RESOLVED && currentChange.isPresent()) {
      throw new IllegalArgumentException("Resolved transition cannot contain a current change");
    }
    if (lifecycle != ChangeLifecycle.RESOLVED && currentChange.isEmpty()) {
      throw new IllegalArgumentException("Current transition requires a schedule change");
    }
  }

  static ChangeTransition current(ChangeLifecycle lifecycle, HashedScheduleChange hashedChange) {
    return new ChangeTransition(
        lifecycle,
        hashedChange.changeKey(),
        hashedChange.metadata(),
        Optional.of(hashedChange.change()));
  }

  static ChangeTransition resolved(ActiveChangeState previous) {
    return new ChangeTransition(
        ChangeLifecycle.RESOLVED, previous.changeKey(), previous.metadata(), Optional.empty());
  }
}
