package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

public final class TelegramTexts {

  public static final String START =
      "Welcome to Vulcan Schedule Monitor. Schedule notifications are supported. "
          + "Secure VULCAN account connection and class selection are not available in this build. "
          + "Never send VULCAN credentials through Telegram.";

  public static final String HELP =
      "Supported commands:\n"
          + "/start - register this private chat\n"
          + "/help - show supported commands\n"
          + "/status - show connection and monitoring status\n"
          + "/subscriptions - list monitored schedule references\n"
          + "/connect - explain the planned secure connection flow";

  public static final String CONNECT =
      "Secure VULCAN connection is not available yet. When implemented, credentials will be "
          + "entered on our HTTPS web page. Never send credentials through Telegram.";

  private TelegramTexts() {}
}
