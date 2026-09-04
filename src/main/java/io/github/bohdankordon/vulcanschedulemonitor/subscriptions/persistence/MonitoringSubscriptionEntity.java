package io.github.bohdankordon.vulcanschedulemonitor.subscriptions.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "monitoring_subscription")
class MonitoringSubscriptionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_user_id", nullable = false)
  private long appUserId;

  @Column(name = "catalog_class_id")
  private Long catalogClassId;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MonitoringSubscriptionEntity() {}

  MonitoringSubscriptionEntity(long appUserId, long catalogClassId, Instant now) {
    this.appUserId = appUserId;
    this.catalogClassId = catalogClassId;
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

  Long catalogClassId() {
    return catalogClassId;
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
