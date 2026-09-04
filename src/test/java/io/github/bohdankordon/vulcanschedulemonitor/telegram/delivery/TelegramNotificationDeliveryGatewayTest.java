package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.DeliveryFailureKind;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDeliveryException;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationEventType;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxMessage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientReference;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TelegramNotificationDeliveryGatewayTest {

  @Test
  void resolvesInternalRecipientAndSendsFormattedTextToStoredPrivateChat() throws Exception {
    var chat = new AtomicLong();
    var text = new AtomicReference<String>();
    TelegramMessageTransport transport =
        (privateChatId, message) -> {
          chat.set(privateChatId);
          text.set(message);
        };
    var gateway = gateway(Optional.of(new TelegramRecipientReference(4001, 5001)), transport);

    gateway.deliver(message());

    assertThat(chat).hasValue(5001);
    assertThat(text.get()).contains("Schedule reference: #1001");
  }

  @Test
  void missingIdentityIsPermanent() {
    assertFailure(
        gateway(Optional.empty(), (chat, text) -> {}),
        DeliveryFailureKind.PERMANENT,
        Optional.empty());
  }

  @Test
  void mapsRateLimitTransientPermanentAndAuthenticationFailures() {
    assertFailure(
        failing(TelegramFailureCategory.RATE_LIMITED, Duration.ofSeconds(19)),
        DeliveryFailureKind.RETRYABLE,
        Optional.of(Duration.ofSeconds(19)));
    assertFailure(
        failing(TelegramFailureCategory.TRANSIENT, null),
        DeliveryFailureKind.RETRYABLE,
        Optional.empty());
    assertFailure(
        failing(TelegramFailureCategory.PERMANENT, null),
        DeliveryFailureKind.PERMANENT,
        Optional.empty());
    assertFailure(
        failing(TelegramFailureCategory.AUTHENTICATION, null),
        DeliveryFailureKind.RETRYABLE,
        Optional.of(Duration.ofMinutes(1)));
  }

  private TelegramNotificationDeliveryGateway failing(
      TelegramFailureCategory category, Duration retryAfter) {
    return gateway(
        Optional.of(new TelegramRecipientReference(4001, 5001)),
        (chat, text) -> {
          throw new TelegramTransportException(category, retryAfter);
        });
  }

  private TelegramNotificationDeliveryGateway gateway(
      Optional<TelegramRecipientReference> recipient, TelegramMessageTransport transport) {
    return new TelegramNotificationDeliveryGateway(
        ignored -> recipient, new TelegramNotificationFormatter(), transport);
  }

  private void assertFailure(
      TelegramNotificationDeliveryGateway gateway,
      DeliveryFailureKind kind,
      Optional<Duration> retryAfter) {
    assertThatThrownBy(() -> gateway.deliver(message()))
        .isInstanceOfSatisfying(
            NotificationDeliveryException.class,
            failure -> {
              assertThat(failure.kind()).isEqualTo(kind);
              assertThat(failure.retryAfter()).isEqualTo(retryAfter);
            });
  }

  private NotificationOutboxMessage message() {
    return new NotificationOutboxMessage(
        1,
        9001,
        NotificationEventType.BASELINE_ESTABLISHED,
        new TrackingScope(1001, LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 6)),
        0,
        null,
        null,
        Instant.parse("2026-09-04T10:00:00Z"),
        1);
  }
}
