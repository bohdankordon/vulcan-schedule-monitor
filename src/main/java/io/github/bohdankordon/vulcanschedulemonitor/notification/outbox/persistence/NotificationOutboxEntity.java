package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeMetadata;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeTransition;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeType;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationEventType;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxClaim;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxMessage;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "notification_outbox",
    indexes = {
      @Index(name = "ix_notification_outbox_pending", columnList = "next_attempt_at, id"),
      @Index(name = "ix_notification_outbox_stale_claim", columnList = "lease_until, id")
    })
class NotificationOutboxEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private NotificationEventType eventType;

  @Column(name = "journal_id", nullable = false)
  private long journalId;

  @Column(name = "week_start", nullable = false)
  private LocalDate weekStart;

  @Column(name = "week_end", nullable = false)
  private LocalDate weekEnd;

  @Column(name = "active_change_count")
  private Integer activeChangeCount;

  @Column(name = "change_key", length = 64)
  private String changeKey;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_type", length = 64)
  private ChangeType changeType;

  @Column(name = "lesson_date")
  private LocalDate lessonDate;

  @Column(name = "lesson_period_id")
  private Long lessonPeriodId;

  @Column(name = "group_id")
  private Long groupId;

  @Column(name = "subject_id")
  private Long subjectId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private NotificationOutboxStatus status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "lease_until")
  private Instant leaseUntil;

  @Column(name = "claim_token")
  private UUID claimToken;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "last_failure_category", length = 16)
  private NotificationOutboxFailureCategory lastFailureCategory;

  protected NotificationOutboxEntity() {}

  static NotificationOutboxEntity baseline(
      TrackingScope scope, int activeChangeCount, Instant occurredAt) {
    NotificationOutboxEntity entity = pending(scope, occurredAt);
    entity.eventType = NotificationEventType.BASELINE_ESTABLISHED;
    entity.activeChangeCount = activeChangeCount;
    return entity;
  }

  static NotificationOutboxEntity transition(
      TrackingScope scope, ChangeTransition transition, Instant occurredAt) {
    NotificationOutboxEntity entity = pending(scope, occurredAt);
    ChangeMetadata metadata = transition.metadata();
    entity.eventType = NotificationEventType.from(transition.lifecycle());
    entity.changeKey = transition.changeKey();
    entity.changeType = metadata.changeType();
    entity.lessonDate = metadata.lessonDate();
    entity.lessonPeriodId = metadata.lessonPeriodId();
    entity.groupId = metadata.groupId();
    entity.subjectId = metadata.subjectId();
    return entity;
  }

  private static NotificationOutboxEntity pending(TrackingScope scope, Instant occurredAt) {
    NotificationOutboxEntity entity = new NotificationOutboxEntity();
    entity.journalId = scope.journalId();
    entity.weekStart = scope.weekStart();
    entity.weekEnd = scope.weekEnd();
    entity.status = NotificationOutboxStatus.PENDING;
    entity.nextAttemptAt = occurredAt;
    entity.createdAt = occurredAt;
    return entity;
  }

  NotificationOutboxClaim claim(Instant leaseExpiry, UUID ownershipToken) {
    status = NotificationOutboxStatus.IN_FLIGHT;
    attemptCount++;
    leaseUntil = leaseExpiry;
    claimToken = ownershipToken;
    return new NotificationOutboxClaim(toMessage(), ownershipToken);
  }

  boolean isOwnedBy(UUID ownershipToken) {
    return status == NotificationOutboxStatus.IN_FLIGHT && ownershipToken.equals(claimToken);
  }

  void delivered(Instant at) {
    status = NotificationOutboxStatus.DELIVERED;
    deliveredAt = at;
    clearClaim();
  }

  void retry(Instant at, NotificationOutboxFailureCategory failureCategory) {
    status = NotificationOutboxStatus.PENDING;
    nextAttemptAt = at;
    lastFailureCategory = failureCategory;
    clearClaim();
  }

  void dead(NotificationOutboxFailureCategory failureCategory) {
    status = NotificationOutboxStatus.DEAD;
    lastFailureCategory = failureCategory;
    clearClaim();
  }

  private void clearClaim() {
    leaseUntil = null;
    claimToken = null;
  }

  private NotificationOutboxMessage toMessage() {
    ChangeMetadata metadata =
        changeType == null
            ? null
            : new ChangeMetadata(changeType, lessonDate, lessonPeriodId, groupId, subjectId);
    return new NotificationOutboxMessage(
        id,
        eventType,
        new TrackingScope(journalId, weekStart, weekEnd),
        activeChangeCount,
        changeKey,
        metadata,
        createdAt,
        attemptCount);
  }
}
