package io.github.bohdankordon.vulcanschedulemonitor.users;

import java.util.Optional;

public interface TelegramRecipientDirectory {

  Optional<TelegramRecipientReference> findByAppUserId(long appUserId);
}
