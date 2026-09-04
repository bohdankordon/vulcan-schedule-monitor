package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "vulcan_connect_token")
class VulcanConnectTokenEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "app_user_id", nullable = false)
  private long appUserId;

  @Column(name = "token_hash", nullable = false, unique = true)
  private byte[] tokenHash;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "failed_attempts", nullable = false)
  private int failedAttempts;

  protected VulcanConnectTokenEntity() {}

  VulcanConnectTokenEntity(long appUserId, byte[] tokenHash, Instant createdAt, Instant expiresAt) {
    this.appUserId = appUserId;
    this.tokenHash = tokenHash.clone();
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  boolean usable(Instant now, int maxAttempts) {
    return consumedAt == null && now.isBefore(expiresAt) && failedAttempts < maxAttempts;
  }

  void failedCredentialAttempt(Instant now, int maxAttempts) {
    if (usable(now, maxAttempts)) {
      failedAttempts++;
    }
  }

  void consume(Instant now) {
    consumedAt = now;
  }

  Long id() {
    return id;
  }

  long appUserId() {
    return appUserId;
  }

  int failedAttempts() {
    return failedAttempts;
  }
}
