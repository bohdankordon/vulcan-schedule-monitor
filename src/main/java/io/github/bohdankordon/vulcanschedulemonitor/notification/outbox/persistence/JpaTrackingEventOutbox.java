package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingEventOutbox;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingResult;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Repository;

@Repository
class JpaTrackingEventOutbox implements TrackingEventOutbox {

  private final NotificationOutboxRepository repository;

  JpaTrackingEventOutbox(NotificationOutboxRepository repository) {
    this.repository = repository;
  }

  @Override
  public void recordReconciliation(TrackingScope scope, TrackingResult result, Instant occurredAt) {
    Objects.requireNonNull(scope, "scope must not be null");
    Objects.requireNonNull(result, "result must not be null");
    Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    if (result.baselineEstablishedNow()) {
      repository.save(
          NotificationOutboxEntity.baseline(scope, result.activeChangeCount(), occurredAt));
      return;
    }
    result.transitions().stream()
        .map(transition -> NotificationOutboxEntity.transition(scope, transition, occurredAt))
        .forEach(repository::save);
  }
}
