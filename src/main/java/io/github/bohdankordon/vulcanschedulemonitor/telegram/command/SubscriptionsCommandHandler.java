package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscription;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import java.util.List;
import java.util.Objects;

public final class SubscriptionsCommandHandler implements TelegramCommandHandler {

  private final MonitoringSubscriptionService subscriptions;
  private final TelegramTextCatalog textCatalog;

  public SubscriptionsCommandHandler(MonitoringSubscriptionService subscriptions) {
    this(subscriptions, new TelegramTextCatalog());
  }

  public SubscriptionsCommandHandler(
      MonitoringSubscriptionService subscriptions, TelegramTextCatalog textCatalog) {
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.SUBSCRIPTIONS;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    var active = subscriptions.activeSubscriptions(context.appUserId());
    List<String> names = active.stream().map(MonitoringSubscription::className).toList();
    return textCatalog.subscriptions(context.language(), names);
  }
}
