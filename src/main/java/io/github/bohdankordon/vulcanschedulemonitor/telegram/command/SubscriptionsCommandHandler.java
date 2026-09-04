package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import java.util.Objects;
import java.util.stream.Collectors;

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
    var references = subscriptions.activeJournalIds(context.appUserId());
    if (references.isEmpty()) {
      return "No active monitored schedules.";
    }
    return "Active monitored schedules: "
        + references.size()
        + "\nSchedule references: "
        + references.stream().map(id -> "#" + id).collect(Collectors.joining(", "));
  }
}
