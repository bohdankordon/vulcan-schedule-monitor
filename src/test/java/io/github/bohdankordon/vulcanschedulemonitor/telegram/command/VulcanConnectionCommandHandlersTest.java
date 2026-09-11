package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringClassSelection;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscription;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectLink;
import java.time.Instant;
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
        .contains("Never send them in Telegram");
  }

  @Test
  void statusContainsOnlySafeStateAndCounts() {
    StatusCommandHandler handler =
        new StatusCommandHandler(
            subscriptions(),
            userId -> new VulcanConnectionStatus(VulcanConnectionStatus.State.CONNECTED, 3));

    assertThat(handler.handle(new TelegramCommandContext(41L, 51L)))
        .contains("VULCAN: Connected", "Available classes: 3", "Monitored classes: 2")
        .doesNotContain("portal", "login", "Telegram ID", "account ID");
  }

  @Test
  void helpSupportsAllLanguages() {
    HelpCommandHandler handler = new HelpCommandHandler();

    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .ENGLISH)))
        .contains(
            "❓ Commands",
            "/connect",
            "/classes",
            "/subscriptions",
            "/status",
            "/language",
            "/start",
            "/help");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .RUSSIAN)))
        .contains(
            "❓ Команды",
            "/connect",
            "/classes",
            "/subscriptions",
            "/status",
            "/language",
            "/start",
            "/help");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .UKRAINIAN)))
        .contains(
            "❓ Команди",
            "/connect",
            "/classes",
            "/subscriptions",
            "/status",
            "/language",
            "/start",
            "/help");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.POLISH)))
        .contains(
            "❓ Polecenia",
            "/connect",
            "/classes",
            "/subscriptions",
            "/status",
            "/language",
            "/start",
            "/help");
  }

  @Test
  void statusSupportsAllLanguages() {
    StatusCommandHandler handler =
        new StatusCommandHandler(
            subscriptions(),
            userId -> new VulcanConnectionStatus(VulcanConnectionStatus.State.CONNECTED, 3));

    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .ENGLISH)))
        .contains(
            "📊 Status", "🟢 VULCAN: Connected", "Available classes: 3", "Monitored classes: 2");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .RUSSIAN)))
        .contains(
            "📊 Состояние",
            "🟢 VULCAN: подключён",
            "Доступных классов: 3",
            "Отслеживаемых классов: 2");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .UKRAINIAN)))
        .contains(
            "📊 Стан", "🟢 VULCAN: підключено", "Доступних класів: 3", "Відстежуваних класів: 2");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.POLISH)))
        .contains("📊 Stan", "🟢 VULCAN: połączony", "Dostępne klasy: 3", "Monitorowane klasy: 2");
  }

  @Test
  void subscriptionsSupportsAllLanguages() {
    SubscriptionsCommandHandler handler = new SubscriptionsCommandHandler(subscriptions());

    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .ENGLISH)))
        .contains("🔔 Monitored classes", "• Synthetic 2A", "• Synthetic 3B");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .RUSSIAN)))
        .contains("🔔 Отслеживаемые классы", "• Synthetic 2A", "• Synthetic 3B");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .UKRAINIAN)))
        .contains("🔔 Відстежувані класи", "• Synthetic 2A", "• Synthetic 3B");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.POLISH)))
        .contains("🔔 Monitorowane klasy", "• Synthetic 2A", "• Synthetic 3B");
  }

  @Test
  void connectSupportsAllLanguages() {
    ConnectCommandHandler handler =
        new ConnectCommandHandler(
            userId -> ConnectLink.enabled("https://connect.example/synthetic"));

    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .ENGLISH)))
        .contains("🔐 Secure VULCAN connection", "https://connect.example/synthetic");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .RUSSIAN)))
        .contains("🔐 Безопасное подключение VULCAN", "https://connect.example/synthetic");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage
                        .UKRAINIAN)))
        .contains("🔐 Безпечне підключення VULCAN", "https://connect.example/synthetic");
    assertThat(
            handler.handle(
                new TelegramCommandContext(
                    41L,
                    51L,
                    io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage.POLISH)))
        .contains("🔐 Bezpieczne połączenie z VULCAN", "https://connect.example/synthetic");
  }

  private static MonitoringSubscriptionService subscriptions() {
    return new MonitoringSubscriptionService() {
      @Override
      public MonitoringSubscription enable(long appUserId, long catalogClassId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public void disable(long appUserId, long catalogClassId) {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<MonitoringSubscription> activeSubscriptions(long appUserId) {
        Instant now = Instant.parse("2026-09-04T10:00:00Z");
        return List.of(
            new MonitoringSubscription(
                1, appUserId, 11, "Synthetic 2A", null, 2026, true, now, now),
            new MonitoringSubscription(
                2, appUserId, 22, "Synthetic 3B", null, 2026, true, now, now));
      }

      @Override
      public List<MonitoringClassSelection> availableClasses(long appUserId) {
        return List.of();
      }

      @Override
      public boolean isSubscribed(long appUserId, long catalogClassId) {
        return false;
      }
    };
  }
}
