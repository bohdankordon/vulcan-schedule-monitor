package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox;

import java.util.Objects;
import java.util.UUID;

public record NotificationOutboxClaim(NotificationOutboxMessage message, UUID ownershipToken) {

  public NotificationOutboxClaim {
    Objects.requireNonNull(message, "message must not be null");
    Objects.requireNonNull(ownershipToken, "ownershipToken must not be null");
  }
}
