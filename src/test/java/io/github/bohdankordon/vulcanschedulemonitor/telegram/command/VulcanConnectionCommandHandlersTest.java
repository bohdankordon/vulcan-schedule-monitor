package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscription;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectLink;
import java.util.List;
import org.junit.jupiter.api.Test;

class VulcanConnectionCommandHandlersTest {

  @Test
  void connectReturnsConfiguredCapabilityWithoutAcceptingCredentials() {
    ConnectCommandHandler handler =
        new ConnectCommandHandler(
            userId -> {
              assertThat(userId).isEqualTo(41L);
              return ConnectLink.enabled("https://connect.example/connect/synthetic-capability");
            });

    assertThat(handler.handle(new TelegramCommandContext(41L, 51L)))
        .contains("https://connect.example/connect/synthetic-capability")
        .contains("Never send VULCAN credentials");
  }

  @Test
  void statusContainsOnlySafeStateAndCounts() {
    StatusCommandHandler handler =
        new StatusCommandHandler(
            subscriptions(),
            userId -> new VulcanConnectionStatus(VulcanConnectionStatus.State.CONNECTED, 3));

    assertThat(handler.handle(new TelegramCommandContext(41L, 51L)))
        .contains("VULCAN: connected", "Available classes: 3", "Active monitoring subscriptions: 2")
        .doesNotContain("portal", "login", "Telegram ID", "account ID");
  }

  private static MonitoringSubscriptionService subscriptions() {
    return new MonitoringSubscriptionService() {
      @Override
      public MonitoringSubscription enable(long appUserId, long journalId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void disable(long appUserId, long journalId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<Long> activeJournalIds(long appUserId) {
        return List.of(1L, 2L);
      }

      @Override
      public boolean isSubscribed(long appUserId, long journalId) {
        return false;
      }
    };
  }
}
