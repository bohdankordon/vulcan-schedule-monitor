package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import java.util.Objects;

public final class StatusCommandHandler implements TelegramCommandHandler {

  private final MonitoringSubscriptionService subscriptions;

  public StatusCommandHandler(MonitoringSubscriptionService subscriptions) {
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.STATUS;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    int count = subscriptions.activeJournalIds(context.appUserId()).size();
    return "Telegram connection registered.\nActive monitoring subscriptions: " + count;
  }
}
