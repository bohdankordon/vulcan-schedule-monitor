package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAccountStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "vulcan_account")
class VulcanAccountEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_user_id", nullable = false, unique = true)
  private long appUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private VulcanAccountStatus status;

  @Column(name = "remember_credentials", nullable = false)
  private boolean rememberCredentials;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "authenticated_at")
  private Instant authenticatedAt;

  protected VulcanAccountEntity() {}

  VulcanAccountEntity(long appUserId, Instant now) {
    this.appUserId = appUserId;
    this.status = VulcanAccountStatus.DISCONNECTED;
    this.createdAt = now;
    this.updatedAt = now;
  }

  void connected(boolean rememberCredentials, Instant now) {
    this.status = VulcanAccountStatus.CONNECTED;
    this.rememberCredentials = rememberCredentials;
    this.updatedAt = now;
    this.authenticatedAt = now;
  }

  void reconnectRequired(Instant now) {
    this.status = VulcanAccountStatus.RECONNECT_REQUIRED;
    this.updatedAt = now;
  }

  Long id() {
    return id;
  }

  long appUserId() {
    return appUserId;
  }

  VulcanAccountStatus status() {
    return status;
  }

  boolean rememberCredentials() {
    return rememberCredentials;
  }
}
