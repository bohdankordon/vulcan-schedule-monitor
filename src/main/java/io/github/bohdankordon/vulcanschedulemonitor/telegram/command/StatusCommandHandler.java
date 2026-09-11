package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatusService;
import java.util.Objects;

public final class StatusCommandHandler implements TelegramCommandHandler {

  private final MonitoringSubscriptionService subscriptions;
  private final VulcanConnectionStatusService connections;
  private final TelegramTextCatalog textCatalog;

  public StatusCommandHandler(MonitoringSubscriptionService subscriptions) {
    this(
        subscriptions,
        appUserId -> new VulcanConnectionStatus(VulcanConnectionStatus.State.NOT_CONNECTED, 0),
        new TelegramTextCatalog());
  }

  public StatusCommandHandler(
      MonitoringSubscriptionService subscriptions, VulcanConnectionStatusService connections) {
    this(subscriptions, connections, new TelegramTextCatalog());
  }

  public StatusCommandHandler(
      MonitoringSubscriptionService subscriptions,
      VulcanConnectionStatusService connections,
      TelegramTextCatalog textCatalog) {
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
    this.connections = Objects.requireNonNull(connections, "connections must not be null");
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.STATUS;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    int count = subscriptions.activeSubscriptions(context.appUserId()).size();
    VulcanConnectionStatus status = connections.statusForUser(context.appUserId());
    return textCatalog.status(context.language(), status.state(), status.activeClassCount(), count);
  }
}
