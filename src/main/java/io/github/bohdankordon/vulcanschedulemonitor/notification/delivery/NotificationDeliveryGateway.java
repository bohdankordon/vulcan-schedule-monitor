package io.github.bohdankordon.vulcanschedulemonitor.notification.delivery;

import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxMessage;

@FunctionalInterface
public interface NotificationDeliveryGateway {

  void deliver(NotificationOutboxMessage message) throws NotificationDeliveryException;
}
