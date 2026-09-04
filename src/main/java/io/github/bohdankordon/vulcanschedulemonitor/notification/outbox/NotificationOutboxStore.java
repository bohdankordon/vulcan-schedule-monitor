package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationOutboxStore {

  List<NotificationOutboxClaim> claimDue(Instant now, int batchSize, Duration leaseDuration);

  boolean markDelivered(long id, UUID ownershipToken, Instant deliveredAt);

  boolean scheduleRetry(
      long id,
      UUID ownershipToken,
      Instant nextAttemptAt,
      NotificationOutboxFailureCategory failureCategory);

  boolean markDead(long id, UUID ownershipToken, NotificationOutboxFailureCategory failureCategory);
}
