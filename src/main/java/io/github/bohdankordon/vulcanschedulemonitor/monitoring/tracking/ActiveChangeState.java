package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import java.time.Instant;
import java.util.Objects;

public record ActiveChangeState(
    String changeKey,
    String fingerprint,
    ChangeMetadata metadata,
    Instant firstSeenAt,
    Instant lastSeenAt) {

  public ActiveChangeState {
    Objects.requireNonNull(changeKey, "changeKey must not be null");
    Objects.requireNonNull(fingerprint, "fingerprint must not be null");
    Objects.requireNonNull(metadata, "metadata must not be null");
    Objects.requireNonNull(firstSeenAt, "firstSeenAt must not be null");
    Objects.requireNonNull(lastSeenAt, "lastSeenAt must not be null");
  }
}
