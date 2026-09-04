package io.github.bohdankordon.vulcanschedulemonitor.subscriptions.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "monitoring_subscription",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_monitoring_subscription_user_journal",
            columnNames = {"app_user_id", "journal_id"}),
    indexes =
        @Index(
            name = "ix_monitoring_subscription_enabled_journal",
            columnList = "journal_id, app_user_id"))
class MonitoringSubscriptionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_user_id", nullable = false)
  private long appUserId;

  @Column(name = "journal_id", nullable = false)
  private long journalId;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MonitoringSubscriptionEntity() {}

  MonitoringSubscriptionEntity(long appUserId, long journalId, Instant now) {
    this.appUserId = appUserId;
    this.journalId = journalId;
    enabled = true;
    createdAt = now;
    updatedAt = now;
  }

  void setEnabled(boolean enabled, Instant now) {
    if (this.enabled != enabled) {
      this.enabled = enabled;
      updatedAt = now;
    }
  }

  Long id() {
    return id;
  }

  long appUserId() {
    return appUserId;
  }

  long journalId() {
    return journalId;
  }

  boolean enabled() {
    return enabled;
  }

  Instant createdAt() {
    return createdAt;
  }

  Instant updatedAt() {
    return updatedAt;
  }
}
