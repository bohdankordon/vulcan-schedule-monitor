package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxClaim;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
class TransactionalNotificationOutboxStore implements NotificationOutboxStore {

  private final NotificationOutboxRepository repository;

  TransactionalNotificationOutboxStore(NotificationOutboxRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<NotificationOutboxClaim> claimDue(
      Instant now, int batchSize, Duration leaseDuration) {
    Objects.requireNonNull(now, "now must not be null");
    Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
    if (batchSize <= 0 || leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("Claim batch and lease duration must be positive");
    }
    Instant leaseExpiry = now.plus(leaseDuration);
    return repository.findDueForUpdate(now, batchSize).stream()
        .map(event -> event.claim(leaseExpiry, UUID.randomUUID()))
        .toList();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markDelivered(long id, UUID ownershipToken, Instant deliveredAt) {
    Objects.requireNonNull(ownershipToken, "ownershipToken must not be null");
    Objects.requireNonNull(deliveredAt, "deliveredAt must not be null");
    return updateOwned(id, ownershipToken, event -> event.delivered(deliveredAt));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean scheduleRetry(
      long id,
      UUID ownershipToken,
      Instant nextAttemptAt,
      NotificationOutboxFailureCategory failureCategory) {
    Objects.requireNonNull(ownershipToken, "ownershipToken must not be null");
    Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
    Objects.requireNonNull(failureCategory, "failureCategory must not be null");
    return updateOwned(id, ownershipToken, event -> event.retry(nextAttemptAt, failureCategory));
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markDead(
      long id, UUID ownershipToken, NotificationOutboxFailureCategory failureCategory) {
    Objects.requireNonNull(ownershipToken, "ownershipToken must not be null");
    Objects.requireNonNull(failureCategory, "failureCategory must not be null");
    return updateOwned(id, ownershipToken, event -> event.dead(failureCategory));
  }

  private boolean updateOwned(
      long id, UUID ownershipToken, java.util.function.Consumer<NotificationOutboxEntity> update) {
    return repository
        .findForUpdate(id)
        .filter(event -> event.isOwnedBy(ownershipToken))
        .map(
            event -> {
              update.accept(event);
              return true;
            })
        .orElse(false);
  }
}
