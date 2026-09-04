package io.github.bohdankordon.vulcanschedulemonitor.users.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "telegram_identity",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_telegram_identity_telegram_user",
          columnNames = "telegram_user_id"),
      @UniqueConstraint(name = "uq_telegram_identity_private_chat", columnNames = "private_chat_id")
    })
class TelegramIdentityEntity {

  @Id
  @Column(name = "app_user_id")
  private Long appUserId;

  @Column(name = "telegram_user_id", nullable = false, unique = true)
  private long telegramUserId;

  @Column(name = "private_chat_id", nullable = false, unique = true)
  private long privateChatId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TelegramIdentityEntity() {}

  TelegramIdentityEntity(long appUserId, long telegramUserId, long privateChatId, Instant now) {
    this.appUserId = appUserId;
    this.telegramUserId = telegramUserId;
    this.privateChatId = privateChatId;
    createdAt = now;
    updatedAt = now;
  }

  void updatePrivateChatId(long privateChatId, Instant now) {
    if (this.privateChatId != privateChatId) {
      this.privateChatId = privateChatId;
      updatedAt = now;
    }
  }

  Long appUserId() {
    return appUserId;
  }

  long telegramUserId() {
    return telegramUserId;
  }

  long privateChatId() {
    return privateChatId;
  }
}
