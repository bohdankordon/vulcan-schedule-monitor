package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDeliveryException;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDeliveryGateway;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxMessage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientDirectory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.VulcanClassCatalog;
import java.time.Duration;
import java.util.Objects;

public final class TelegramNotificationDeliveryGateway implements NotificationDeliveryGateway {

  private static final Duration AUTHENTICATION_RETRY_DELAY = Duration.ofMinutes(1);

  private final TelegramRecipientDirectory recipients;
  private final VulcanClassCatalog catalog;
  private final TelegramNotificationFormatter formatter;
  private final TelegramMessageTransport transport;

  public TelegramNotificationDeliveryGateway(
      TelegramRecipientDirectory recipients,
      VulcanClassCatalog catalog,
      TelegramNotificationFormatter formatter,
      TelegramMessageTransport transport) {
    this.recipients = Objects.requireNonNull(recipients, "recipients must not be null");
    this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
  }

  @Override
  public void deliver(NotificationOutboxMessage message) throws NotificationDeliveryException {
    var recipient = recipients.findByAppUserId(message.recipientUserId());
    if (recipient.isEmpty()) {
      throw NotificationDeliveryException.permanent();
    }
    var catalogClass = catalog.findForUser(message.recipientUserId(), message.catalogClassId());
    if (catalogClass.isEmpty()) {
      throw NotificationDeliveryException.permanent();
    }
    try {
      transport.sendPlainText(
          recipient.orElseThrow().privateChatId(),
          formatter.format(message, catalogClass.orElseThrow().name()));
    } catch (TelegramTransportException failure) {
      if (failure.category() == TelegramFailureCategory.PERMANENT) {
        throw NotificationDeliveryException.permanent();
      }
      if (failure.category() == TelegramFailureCategory.RATE_LIMITED) {
        throw NotificationDeliveryException.retryable(failure.retryAfter().orElseThrow());
      }
      if (failure.category() == TelegramFailureCategory.AUTHENTICATION) {
        throw NotificationDeliveryException.retryable(AUTHENTICATION_RETRY_DELAY);
      }
      throw NotificationDeliveryException.retryable();
    }
  }
}
