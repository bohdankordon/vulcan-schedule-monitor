package io.github.bohdankordon.vulcanschedulemonitor.users.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramLanguagePreferences;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaTelegramLanguagePreferences implements TelegramLanguagePreferences {

  private final TelegramIdentityRepository repository;
  private final Clock clock;

  JpaTelegramLanguagePreferences(TelegramIdentityRepository repository, Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public TelegramLanguage getLanguage(long appUserId) {
    return repository
        .findById(appUserId)
        .map(TelegramIdentityEntity::language)
        .orElse(TelegramLanguage.ENGLISH);
  }

  @Override
  @Transactional
  public void setLanguage(long appUserId, TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    TelegramIdentityEntity identity =
        repository
            .findById(appUserId)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Telegram identity not found for application user: " + appUserId));
    identity.updateLanguage(language, clock.instant());
  }
}
