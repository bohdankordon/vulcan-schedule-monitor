package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramLanguagePreferences;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

public final class TelegramCallbackRouter {

  private static final Logger LOGGER = LoggerFactory.getLogger(TelegramCallbackRouter.class);

  private final ClassSelectionCallbackParser classParser;
  private final LanguageCallbackParser languageParser;
  private final TelegramIdentityRegistration identities;
  private final TelegramLanguagePreferences preferences;
  private final TelegramTextCatalog textCatalog;
  private final MonitoringSubscriptionService subscriptions;
  private final ClassSelectionController classes;
  private final LanguageSelectionController languageController;
  private final TelegramInteractiveTransport transport;

  public TelegramCallbackRouter(
      ClassSelectionCallbackParser classParser,
      TelegramIdentityRegistration identities,
      MonitoringSubscriptionService subscriptions,
      ClassSelectionController classes,
      TelegramInteractiveTransport transport) {
    this(
        classParser,
        new LanguageCallbackParser(),
        identities,
        new DefaultTelegramLanguagePreferences(),
        new TelegramTextCatalog(),
        subscriptions,
        classes,
        new LanguageSelectionController(
            new DefaultTelegramLanguagePreferences(),
            new TelegramTextCatalog(),
            transport,
            new io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                .TelegramCommandMenuService(
                new io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu
                    .TelegramCommandMenuTransport() {
                  @Override
                  public void configureDefaultPrivateCommands(
                      java.util.List<org.telegram.telegrambots.meta.api.objects.commands.BotCommand>
                          commands) {}

                  @Override
                  public void configureChatCommands(
                      long privateChatId,
                      java.util.List<org.telegram.telegrambots.meta.api.objects.commands.BotCommand>
                          commands) {}

                  @Override
                  public void configureChatMenuButton(long privateChatId) {}
                },
                new TelegramTextCatalog())),
        transport);
  }

  private static final class DefaultTelegramLanguagePreferences
      implements TelegramLanguagePreferences {
    @Override
    public TelegramLanguage getLanguage(long appUserId) {
      return TelegramLanguage.ENGLISH;
    }

    @Override
    public void setLanguage(long appUserId, TelegramLanguage language) {}
  }

  public TelegramCallbackRouter(
      ClassSelectionCallbackParser classParser,
      LanguageCallbackParser languageParser,
      TelegramIdentityRegistration identities,
      TelegramLanguagePreferences preferences,
      TelegramTextCatalog textCatalog,
      MonitoringSubscriptionService subscriptions,
      ClassSelectionController classes,
      LanguageSelectionController languageController,
      TelegramInteractiveTransport transport) {
    this.classParser = Objects.requireNonNull(classParser, "classParser must not be null");
    this.languageParser = Objects.requireNonNull(languageParser, "languageParser must not be null");
    this.identities = Objects.requireNonNull(identities, "identities must not be null");
    this.preferences = Objects.requireNonNull(preferences, "preferences must not be null");
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
    this.classes = Objects.requireNonNull(classes, "classes must not be null");
    this.languageController =
        Objects.requireNonNull(languageController, "languageController must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
  }

  public void route(CallbackQuery query) {
    if (!isPrivateHumanCallback(query)) {
      return;
    }
    String data = query.getData();
    if (data == null) {
      return;
    }

    if (data.startsWith("l1:")) {
      handleLanguageCallback(query);
      return;
    }

    if (data.startsWith("c1:")) {
      handleClassCallback(query);
      return;
    }

    // Malformed or unknown callback version
    answer(query.getId(), textCatalog.invalidClassControl(TelegramLanguage.ENGLISH));
  }

  private void handleLanguageCallback(CallbackQuery query) {
    var parsed = languageParser.parse(query.getData());
    if (parsed.isEmpty()) {
      answer(query.getId(), textCatalog.invalidLanguageControl(TelegramLanguage.ENGLISH));
      return;
    }

    long telegramUserId = query.getFrom().getId();
    long privateChatId = query.getMessage().getChatId();
    int messageId = query.getMessage().getMessageId();
    long appUserId = identities.registerOrUpdate(telegramUserId, privateChatId).id();

    languageController.handleCallback(
        parsed.orElseThrow(), appUserId, privateChatId, messageId, query.getId());
  }

  private void handleClassCallback(CallbackQuery query) {
    var parsed = classParser.parse(query.getData());
    if (parsed.isEmpty()) {
      answer(query.getId(), textCatalog.invalidClassControl(TelegramLanguage.ENGLISH));
      return;
    }

    long telegramUserId = query.getFrom().getId();
    long privateChatId = query.getMessage().getChatId();
    int messageId = query.getMessage().getMessageId();
    long appUserId = identities.registerOrUpdate(telegramUserId, privateChatId).id();
    TelegramLanguage language = preferences.getLanguage(appUserId);

    ClassSelectionCallback callback = parsed.orElseThrow();
    String acknowledgment = textCatalog.classListRefreshed(language);
    if (callback.action() == ClassSelectionCallback.Action.TOGGLE) {
      try {
        if (subscriptions.isSubscribed(appUserId, callback.catalogClassId())) {
          subscriptions.disable(appUserId, callback.catalogClassId());
          acknowledgment = textCatalog.monitoringDisabled(language);
        } else {
          subscriptions.enable(appUserId, callback.catalogClassId());
          acknowledgment = textCatalog.monitoringEnabled(language);
        }
      } catch (IllegalArgumentException rejected) {
        acknowledgment = textCatalog.classUnavailable(language);
      }
    }

    try {
      classes.edit(appUserId, privateChatId, messageId, callback.page(), language);
      LOGGER.debug("Telegram class-selection callback processed: action={}", callback.action());
    } catch (TelegramTransportException failure) {
      LOGGER.warn("Telegram class-selection refresh failed: category={}", failure.category());
    } finally {
      answer(query.getId(), acknowledgment);
    }
  }

  private void answer(String callbackQueryId, String text) {
    try {
      transport.answerCallback(callbackQueryId, text);
    } catch (TelegramTransportException failure) {
      LOGGER.warn("Telegram callback answer failed: category={}", failure.category());
    }
  }

  private static boolean isPrivateHumanCallback(CallbackQuery query) {
    return query != null
        && query.getId() != null
        && query.getFrom() != null
        && query.getFrom().getId() != null
        && !Boolean.TRUE.equals(query.getFrom().getIsBot())
        && query.getMessage() != null
        && query.getMessage().getMessageId() != null
        && query.getMessage().getChat() != null
        && Boolean.TRUE.equals(query.getMessage().getChat().isUserChat());
  }
}
