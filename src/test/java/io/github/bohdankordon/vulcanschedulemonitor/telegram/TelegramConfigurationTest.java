package io.github.bohdankordon.vulcanschedulemonitor.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TelegramConfigurationTest {

  @Test
  void enabledBotRejectsBlankTokenWithSanitizedConfigurationFailure() {
    new ApplicationContextRunner()
        .withUserConfiguration(TelegramConfiguration.class)
        .withPropertyValues("telegram.bot.enabled=true", "telegram.bot.token=   ")
        .run(
            context -> {
              assertThat(context).hasFailed();
              Throwable root = context.getStartupFailure();
              while (root.getCause() != null) {
                root = root.getCause();
              }
              assertThat(root)
                  .hasMessageContaining("TELEGRAM_BOT_TOKEN is blank")
                  .hasMessageNotContaining("telegram.bot.token=   ");
            });
  }

  @Test
  void tokenBearingPropertiesRedactToString() {
    var properties = new TelegramBotProperties();
    properties.setEnabled(true);
    properties.setToken("synthetic-secret-value");
    assertThat(properties.toString())
        .contains("enabled=true", "<redacted>")
        .doesNotContain("synthetic-secret-value");
  }

  @Test
  void commandMenuServiceWiresWithCommandMenuTransport() {
    new ApplicationContextRunner()
        .withUserConfiguration(TelegramConfiguration.class, TestDependenciesConfiguration.class)
        .withPropertyValues(
            "telegram.bot.enabled=true",
            "telegram.bot.token=synthetic-test-token",
            "telegram.dispatch.interval=PT2S",
            "telegram.dispatch.initial-delay=PT2S")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .hasSingleBean(
                      io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                          .TelegramCommandMenuService.class);
              assertThat(context)
                  .getBean(
                      io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                          .TelegramCommandMenuTransport.class)
                  .isInstanceOf(
                      io.github.bohdankordon.vulcanschedulemonitor.telegram.transport
                          .TelegramBotsMessageTransport.class);
            });
  }

  interface TestTransportSeam
      extends io.github.bohdankordon.vulcanschedulemonitor.telegram.transport
              .TelegramMessageTransport,
          io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive
              .TelegramInteractiveTransport,
          io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
              .TelegramCommandMenuTransport {}

  @Test
  void customTransportSeamIsWiredIntoConfiguration() {
    var customTransport = org.mockito.Mockito.mock(TestTransportSeam.class);
    new ApplicationContextRunner()
        .withUserConfiguration(TelegramConfiguration.class, TestDependenciesConfiguration.class)
        .withBean(TestTransportSeam.class, () -> customTransport)
        .withPropertyValues(
            "telegram.bot.enabled=true",
            "telegram.bot.token=synthetic-test-token",
            "telegram.dispatch.interval=PT2S",
            "telegram.dispatch.initial-delay=PT2S")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .doesNotHaveBean(
                      io.github.bohdankordon.vulcanschedulemonitor.telegram.transport
                          .TelegramBotsMessageTransport.class);
              assertThat(context)
                  .hasSingleBean(
                      io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                          .TelegramCommandMenuService.class);
            });
  }

  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static class TestDependenciesConfiguration {
    @org.springframework.context.annotation.Bean
    java.time.Clock clock() {
      return java.time.Clock.systemUTC();
    }

    @org.springframework.context.annotation.Bean
    io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration identities() {
      return (userId, chatId) ->
          new io.github.bohdankordon.vulcanschedulemonitor.users.ApplicationUser(
              userId, true, java.time.Instant.now(), java.time.Instant.now());
    }

    @org.springframework.context.annotation.Bean
    io.github.bohdankordon.vulcanschedulemonitor.users.TelegramLanguagePreferences preferences() {
      return new io.github.bohdankordon.vulcanschedulemonitor.users.TelegramLanguagePreferences() {
        @Override
        public TelegramLanguage getLanguage(long appUserId) {
          return TelegramLanguage.ENGLISH;
        }

        @Override
        public void setLanguage(long appUserId, TelegramLanguage language) {}
      };
    }

    @org.springframework.context.annotation.Bean
    io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService
        subscriptions() {
      return org.mockito.Mockito.mock(
          io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService
              .class);
    }

    @org.springframework.context.annotation.Bean
    io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatusService
        connections() {
      return org.mockito.Mockito.mock(
          io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection
              .VulcanConnectionStatusService.class);
    }

    @org.springframework.context.annotation.Bean
    io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.VulcanConnectLinkService
        links() {
      return org.mockito.Mockito.mock(
          io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token
              .VulcanConnectLinkService.class);
    }

    @org.springframework.context.annotation.Bean
    io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientDirectory recipients() {
      return org.mockito.Mockito.mock(
          io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientDirectory.class);
    }

    @org.springframework.context.annotation.Bean
    io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.VulcanClassCatalog
        catalog() {
      return org.mockito.Mockito.mock(
          io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.VulcanClassCatalog
              .class);
    }

    @org.springframework.context.annotation.Bean
    io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxStore
        outbox() {
      return org.mockito.Mockito.mock(
          io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxStore
              .class);
    }
  }
}
