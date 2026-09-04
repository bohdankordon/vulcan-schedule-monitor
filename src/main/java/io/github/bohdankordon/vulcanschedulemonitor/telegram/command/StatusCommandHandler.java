package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatusService;
import java.util.Objects;

public final class StatusCommandHandler implements TelegramCommandHandler {

  private final MonitoringSubscriptionService subscriptions;
  private final VulcanConnectionStatusService connections;

  public StatusCommandHandler(MonitoringSubscriptionService subscriptions) {
    this(
        subscriptions,
        appUserId -> new VulcanConnectionStatus(VulcanConnectionStatus.State.NOT_CONNECTED, 0));
  }

  public StatusCommandHandler(
      MonitoringSubscriptionService subscriptions, VulcanConnectionStatusService connections) {
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
    this.connections = Objects.requireNonNull(connections, "connections must not be null");
  }

  @Override
  public TelegramCommand supportedCommand() {
    return TelegramCommand.STATUS;
  }

  @Override
  public String handle(TelegramCommandContext context) {
    int count = subscriptions.activeJournalIds(context.appUserId()).size();
    VulcanConnectionStatus status = connections.statusForUser(context.appUserId());
    String vulcan =
        switch (status.state()) {
          case CONNECTED -> "connected";
          case RECONNECT_REQUIRED -> "reconnect required";
          case NOT_CONNECTED -> "not connected";
        };
    return "Telegram connection registered.\nVULCAN: "
        + vulcan
        + "\nAvailable classes: "
        + status.activeClassCount()
        + "\nActive monitoring subscriptions: "
        + count;
  }
}
