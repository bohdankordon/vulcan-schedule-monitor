package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

public final class TelegramTexts {

  public static final String START =
      "Welcome to Vulcan Schedule Monitor. Schedule notifications are supported. "
          + "Use /connect when secure VULCAN connection is enabled. Class selection is not yet "
          + "available. "
          + "Never send VULCAN credentials through Telegram.";

  public static final String HELP =
      "Supported commands:\n"
          + "/start - register this private chat\n"
          + "/help - show supported commands\n"
          + "/status - show connection and monitoring status\n"
          + "/subscriptions - list monitored schedule references\n"
          + "/connect - obtain a short-lived secure connection link";

  public static final String CONNECT_DISABLED =
      "Secure VULCAN connection is disabled by the operator. Credentials belong only on the "
          + "HTTPS web page when enabled. Never send credentials through Telegram.";

  private TelegramTexts() {}
}
