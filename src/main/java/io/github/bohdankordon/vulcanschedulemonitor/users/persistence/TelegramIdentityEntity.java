package io.github.bohdankordon.vulcanschedulemonitor.users.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;

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

  @Column(name = "language_code", nullable = false)
  private String languageCode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected TelegramIdentityEntity() {}

  TelegramIdentityEntity(long appUserId, long telegramUserId, long privateChatId, Instant now) {
    this(appUserId, telegramUserId, privateChatId, TelegramLanguage.ENGLISH, now);
  }

  TelegramIdentityEntity(
      long appUserId,
      long telegramUserId,
      long privateChatId,
      TelegramLanguage language,
      Instant now) {
    this.appUserId = appUserId;
    this.telegramUserId = telegramUserId;
    this.privateChatId = privateChatId;
    this.languageCode = Objects.requireNonNull(language, "language must not be null").code();
    this.createdAt = now;
    this.updatedAt = now;
  }

  void updatePrivateChatId(long privateChatId, Instant now) {
    if (this.privateChatId != privateChatId) {
      this.privateChatId = privateChatId;
      updatedAt = now;
    }
  }

  void updateLanguage(TelegramLanguage language, Instant now) {
    Objects.requireNonNull(language, "language must not be null");
    if (language() != language) {
      this.languageCode = language.code();
      this.updatedAt = now;
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

  TelegramLanguage language() {
    return TelegramLanguage.fromCode(languageCode);
  }
}
