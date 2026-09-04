package io.github.bohdankordon.vulcanschedulemonitor.users.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_user")
class AppUserEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AppUserEntity() {}

  AppUserEntity(Instant now) {
    active = true;
    createdAt = now;
    updatedAt = now;
  }

  void reactivate(Instant now) {
    if (!active) {
      active = true;
      updatedAt = now;
    }
  }

  Long id() {
    return id;
  }

  boolean active() {
    return active;
  }

  Instant createdAt() {
    return createdAt;
  }

  Instant updatedAt() {
    return updatedAt;
  }
}
