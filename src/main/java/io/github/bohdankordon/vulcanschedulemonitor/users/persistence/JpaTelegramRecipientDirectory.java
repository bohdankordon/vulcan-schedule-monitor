package io.github.bohdankordon.vulcanschedulemonitor.users.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientDirectory;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientReference;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
class JpaTelegramRecipientDirectory implements TelegramRecipientDirectory {

  private final TelegramIdentityRepository repository;

  JpaTelegramRecipientDirectory(TelegramIdentityRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<TelegramRecipientReference> findByAppUserId(long appUserId) {
    return repository
        .findById(appUserId)
        .map(
            identity ->
                new TelegramRecipientReference(
                    identity.telegramUserId(), identity.privateChatId()));
  }
}
