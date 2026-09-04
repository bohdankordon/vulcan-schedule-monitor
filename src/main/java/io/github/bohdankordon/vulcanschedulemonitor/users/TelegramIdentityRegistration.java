package io.github.bohdankordon.vulcanschedulemonitor.users;

public interface TelegramIdentityRegistration {

  ApplicationUser registerOrUpdate(long telegramUserId, long privateChatId);
}
