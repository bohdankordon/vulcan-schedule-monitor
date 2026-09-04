package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

public final class TelegramTexts {

  public static final String START =
      "Welcome to Vulcan Schedule Monitor. Schedule notifications are supported. "
          + "Use /connect when secure VULCAN connection is enabled, then /classes to choose "
          + "which classes to monitor. "
          + "Never send VULCAN credentials through Telegram.";

  public static final String HELP =
      "Supported commands:\n"
          + "/start - register this private chat\n"
          + "/help - show supported commands\n"
          + "/status - show connection and monitoring status\n"
          + "/classes - choose classes to monitor\n"
          + "/subscriptions - list monitored classes\n"
          + "/connect - obtain a short-lived secure connection link";

  public static final String CONNECT_DISABLED =
      "Secure VULCAN connection is disabled by the operator. Credentials belong only on the "
          + "HTTPS web page when enabled. Never send credentials through Telegram.";

  private TelegramTexts() {}
}
