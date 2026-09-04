package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import java.util.Objects;

public final class SubscriptionsCommandHandler implements TelegramCommandHandler {

  private final MonitoringSubscriptionService subscriptions;

  public SubscriptionsCommandHandler(MonitoringSubscriptionService subscriptions) {
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.SUBSCRIPTIONS;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    var active = subscriptions.activeSubscriptions(context.appUserId());
    if (active.isEmpty()) {
      return "No classes are currently monitored.";
    }
    return "Active monitoring:\n"
        + active.stream()
            .map(subscription -> "• " + subscription.className())
            .collect(java.util.stream.Collectors.joining("\n"));
  }
}
