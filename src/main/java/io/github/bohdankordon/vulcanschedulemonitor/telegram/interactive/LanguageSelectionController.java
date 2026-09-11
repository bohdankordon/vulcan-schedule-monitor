package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu.TelegramCommandMenuService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramLanguagePreferences;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LanguageSelectionController {

  private static final Logger LOGGER = LoggerFactory.getLogger(LanguageSelectionController.class);

  private final TelegramLanguagePreferences preferences;
  private final TelegramTextCatalog textCatalog;
  private final TelegramInteractiveTransport transport;
  private final TelegramCommandMenuService menuService;

  public LanguageSelectionController(
      TelegramLanguagePreferences preferences,
      TelegramTextCatalog textCatalog,
      TelegramInteractiveTransport transport,
      TelegramCommandMenuService menuService) {
    this.preferences = Objects.requireNonNull(preferences, "preferences must not be null");
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.menuService = Objects.requireNonNull(menuService, "menuService must not be null");
  }

  public void sendStartChooser(long privateChatId) throws TelegramTransportException {
    TelegramInteractiveMessage message =
        new TelegramInteractiveMessage(
            textCatalog.initialLanguagePrompt(),
            buildKeyboard(LanguageCallback.Source.START, null));
    transport.send(privateChatId, message);
  }

  public void sendLanguageChooser(long privateChatId, TelegramLanguage currentLanguage)
      throws TelegramTransportException {
    TelegramInteractiveMessage message =
        new TelegramInteractiveMessage(
            textCatalog.initialLanguagePrompt(),
            buildKeyboard(LanguageCallback.Source.COMMAND, currentLanguage));
    transport.send(privateChatId, message);
  }

  public void handleCallback(
      LanguageCallback callback,
      long appUserId,
      long privateChatId,
      int messageId,
      String callbackQueryId) {
    Objects.requireNonNull(callback, "callback must not be null");

    preferences.setLanguage(appUserId, callback.language());
    menuService.synchronizeChatMenu(privateChatId, callback.language());

    String replyText =
        callback.source() == LanguageCallback.Source.START
            ? textCatalog.welcome(callback.language())
            : textCatalog.languageChanged(callback.language());

    String acknowledgment = textCatalog.languageChanged(callback.language());

    try {
      transport.edit(
          privateChatId, messageId, new TelegramInteractiveMessage(replyText, List.of()));
      LOGGER.debug(
          "Telegram language selection processed: source={}, language={}",
          callback.source(),
          callback.language().code());
    } catch (TelegramTransportException failure) {
      LOGGER.warn("Telegram language selection edit failed: category={}", failure.category());
    } finally {
      try {
        transport.answerCallback(callbackQueryId, acknowledgment);
      } catch (TelegramTransportException failure) {
        LOGGER.warn("Telegram language callback answer failed: category={}", failure.category());
      }
    }
  }

  private List<List<TelegramInlineButton>> buildKeyboard(
      LanguageCallback.Source source, TelegramLanguage selectedLanguage) {
    String prefix = "l1:" + source.code() + ":";
    return List.of(
        List.of(
            button(TelegramLanguage.ENGLISH, prefix, selectedLanguage),
            button(TelegramLanguage.RUSSIAN, prefix, selectedLanguage)),
        List.of(
            button(TelegramLanguage.UKRAINIAN, prefix, selectedLanguage),
            button(TelegramLanguage.POLISH, prefix, selectedLanguage)));
  }

  private TelegramInlineButton button(
      TelegramLanguage language, String callbackPrefix, TelegramLanguage selectedLanguage) {
    String label =
        selectedLanguage == language
            ? "✅ " + textCatalog.languageDisplayName(language)
            : textCatalog.languageDisplayName(language);
    return new TelegramInlineButton(label, callbackPrefix + language.code());
  }
}
