package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingEventOutbox;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingResult;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.notification.recipient.NotificationRecipientProvider;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
class JpaTrackingEventOutbox implements TrackingEventOutbox {

  private final NotificationOutboxRepository repository;
  private final NotificationRecipientProvider recipientProvider;

  JpaTrackingEventOutbox(
      NotificationOutboxRepository repository, NotificationRecipientProvider recipientProvider) {
    this.repository = repository;
    this.recipientProvider = recipientProvider;
  }

  @Override
  public void recordReconciliation(TrackingScope scope, TrackingResult result, Instant occurredAt) {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(result, "result must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    for (long recipientUserId : recipientProvider.activeRecipientUserIds(scope.journalId())) {
      if (result.baselineEstablishedNow()) {
        repository.saveAndFlush(
            NotificationOutboxEntity.baseline(
                scope, recipientUserId, result.activeChangeCount(), occurredAt));
      } else {
        result.transitions().stream()
            .map(
                transition ->
                    NotificationOutboxEntity.transition(
                        scope, recipientUserId, transition, occurredAt))
            .forEach(repository::saveAndFlush);
      }
    }
  }
}
