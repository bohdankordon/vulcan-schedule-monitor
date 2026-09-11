package io.github.bohdankordon.vulcanschedulemonitor.telegram;

import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDispatchPolicy;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxStore;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.ConnectCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.HelpCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.StartCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.StatusCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.SubscriptionsCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommandParser;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu.TelegramCommandMenuService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationDeliveryGateway;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationDispatchScheduler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationFormatter;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.ClassSelectionCallbackParser;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.ClassSelectionController;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.LanguageCallbackParser;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.LanguageSelectionController;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.TelegramCallbackRouter;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.TelegramInteractiveTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramBotsLongPollingEngineFactory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramLongPollingEngineFactory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramLongPollingRuntime;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramLongPollingSupervisor;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramBotsMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.update.TelegramUpdateConsumer;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.update.TelegramUpdateRouter;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramLanguagePreferences;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientDirectory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatusService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.VulcanClassCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.VulcanConnectLinkService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({TelegramBotProperties.class, TelegramDispatchProperties.class})
@ConditionalOnProperty(name = "telegram.bot.enabled", havingValue = "true")
public class TelegramConfiguration {

  private final TelegramBotProperties botProperties;

  public TelegramConfiguration(
      TelegramBotProperties botProperties, TelegramDispatchProperties dispatchProperties) {
    this.botProperties = botProperties;
    if (botProperties.getToken().isBlank()) {
      throw new IllegalStateException("Telegram bot is enabled but TELEGRAM_BOT_TOKEN is blank");
    }
    validateDuration(dispatchProperties.getInterval(), "telegram.dispatch.interval");
    validateDuration(dispatchProperties.getInitialDelay(), "telegram.dispatch.initial-delay");
  }

  @Bean
  TelegramProviderAvailabilityGate telegramProviderAvailabilityGate(Clock clock) {
    return new TelegramProviderAvailabilityGate(clock);
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean(TelegramMessageTransport.class)
  TelegramBotsMessageTransport telegramMessageTransport(TelegramProviderAvailabilityGate gate) {
    return new TelegramBotsMessageTransport(botProperties.getToken(), gate);
  }

  @Bean
  @ConditionalOnMissingBean(TelegramLongPollingEngineFactory.class)
  TelegramLongPollingEngineFactory telegramLongPollingEngineFactory() {
    return new TelegramBotsLongPollingEngineFactory();
  }

  @Bean
  TelegramTextCatalog telegramTextCatalog() {
    return new TelegramTextCatalog();
  }

  @Bean
  TelegramCommandMenuService telegramCommandMenuService(
      TelegramBotsMessageTransport transport, TelegramTextCatalog textCatalog) {
    return new TelegramCommandMenuService(transport, textCatalog);
  }

  @Bean
  TelegramCommandParser telegramCommandParser() {
    return new TelegramCommandParser();
  }

  @Bean
  ClassSelectionCallbackParser classSelectionCallbackParser() {
    return new ClassSelectionCallbackParser();
  }

  @Bean
  LanguageCallbackParser languageCallbackParser() {
    return new LanguageCallbackParser();
  }

  @Bean
  LanguageSelectionController languageSelectionController(
      TelegramLanguagePreferences preferences,
      TelegramTextCatalog textCatalog,
      TelegramInteractiveTransport transport,
      TelegramCommandMenuService menuService) {
    return new LanguageSelectionController(preferences, textCatalog, transport, menuService);
  }

  @Bean
  ClassSelectionController classSelectionController(
      MonitoringSubscriptionService subscriptions,
      VulcanConnectionStatusService connections,
      TelegramTextCatalog textCatalog,
      TelegramMessageTransport transport,
      TelegramInteractiveTransport interactiveTransport) {
    return new ClassSelectionController(
        subscriptions, connections, textCatalog, transport, interactiveTransport);
  }

  @Bean
  TelegramCallbackRouter telegramCallbackRouter(
      ClassSelectionCallbackParser classParser,
      LanguageCallbackParser languageParser,
      TelegramIdentityRegistration identities,
      TelegramLanguagePreferences preferences,
      TelegramTextCatalog textCatalog,
      MonitoringSubscriptionService subscriptions,
      ClassSelectionController classes,
      LanguageSelectionController languageController,
      TelegramInteractiveTransport transport) {
    return new TelegramCallbackRouter(
        classParser,
        languageParser,
        identities,
        preferences,
        textCatalog,
        subscriptions,
        classes,
        languageController,
        transport);
  }

  @Bean
  TelegramCommandHandler startCommandHandler(TelegramTextCatalog textCatalog) {
    return new StartCommandHandler(textCatalog);
  }

  @Bean
  TelegramCommandHandler helpCommandHandler(TelegramTextCatalog textCatalog) {
    return new HelpCommandHandler(textCatalog);
  }

  @Bean
  TelegramCommandHandler connectCommandHandler(
      VulcanConnectLinkService links, TelegramTextCatalog textCatalog) {
    return new ConnectCommandHandler(links, textCatalog);
  }

  @Bean
  TelegramCommandHandler statusCommandHandler(
      MonitoringSubscriptionService subscriptions,
      VulcanConnectionStatusService connections,
      TelegramTextCatalog textCatalog) {
    return new StatusCommandHandler(subscriptions, connections, textCatalog);
  }

  @Bean
  TelegramCommandHandler subscriptionsCommandHandler(
      MonitoringSubscriptionService subscriptions, TelegramTextCatalog textCatalog) {
    return new SubscriptionsCommandHandler(subscriptions, textCatalog);
  }

  @Bean
  TelegramUpdateRouter telegramUpdateRouter(
      TelegramCommandParser parser,
      TelegramIdentityRegistration identities,
      TelegramLanguagePreferences preferences,
      TelegramMessageTransport transport,
      List<TelegramCommandHandler> handlers,
      TelegramCallbackRouter callbackRouter,
      ClassSelectionController classes,
      LanguageSelectionController languageController) {
    return new TelegramUpdateRouter(
        parser,
        identities,
        preferences,
        transport,
        handlers,
        callbackRouter,
        classes,
        languageController);
  }

  @Bean
  TelegramUpdateConsumer telegramUpdateConsumer(TelegramUpdateRouter router) {
    return new TelegramUpdateConsumer(router);
  }

  @Bean(destroyMethod = "close")
  TelegramLongPollingRuntime telegramLongPollingRuntime(
      TelegramLongPollingEngineFactory engineFactory,
      TelegramUpdateConsumer consumer,
      TelegramProviderAvailabilityGate gate,
      Clock clock,
      TelegramCommandMenuService menuService) {
    return new TelegramLongPollingRuntime(
        botProperties.getToken(), engineFactory, consumer, gate, clock, menuService);
  }

  @Bean
  TelegramLongPollingSupervisor telegramLongPollingSupervisor(TelegramLongPollingRuntime runtime) {
    return new TelegramLongPollingSupervisor(runtime);
  }

  @Bean
  TelegramNotificationFormatter telegramNotificationFormatter(TelegramTextCatalog textCatalog) {
    return new TelegramNotificationFormatter(textCatalog);
  }

  @Bean
  TelegramNotificationDeliveryGateway telegramNotificationDeliveryGateway(
      TelegramRecipientDirectory recipients,
      VulcanClassCatalog catalog,
      TelegramNotificationFormatter formatter,
      TelegramMessageTransport transport) {
    return new TelegramNotificationDeliveryGateway(recipients, catalog, formatter, transport);
  }

  @Bean
  NotificationOutboxDispatcher telegramNotificationOutboxDispatcher(
      NotificationOutboxStore store, TelegramNotificationDeliveryGateway gateway, Clock clock) {
    return new NotificationOutboxDispatcher(
        store,
        gateway,
        clock,
        new NotificationDispatchPolicy(1, Duration.ofMinutes(2), 5, Duration.ofMinutes(15)));
  }

  @Bean
  TelegramNotificationDispatchScheduler telegramNotificationDispatchScheduler(
      NotificationOutboxDispatcher dispatcher, TelegramProviderAvailabilityGate gate) {
    return new TelegramNotificationDispatchScheduler(dispatcher, gate);
  }

  private static void validateDuration(Duration duration, String property) {
    if (duration == null || duration.isNegative() || duration.isZero()) {
      throw new IllegalStateException(property + " must be positive");
    }
  }
}
