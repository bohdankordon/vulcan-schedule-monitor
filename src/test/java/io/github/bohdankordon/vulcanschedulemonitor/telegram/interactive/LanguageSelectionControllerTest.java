package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu.TelegramCommandMenuService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu.TelegramCommandMenuTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramLanguagePreferences;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

class LanguageSelectionControllerTest {

  private final TelegramTextCatalog catalog = new TelegramTextCatalog();
  private final RecordingInteractiveTransport transport = new RecordingInteractiveTransport();
  private final RecordingPreferences preferences = new RecordingPreferences();
  private final RecordingMenuTransport menuTransport = new RecordingMenuTransport();
  private final TelegramCommandMenuService menuService =
      new TelegramCommandMenuService(menuTransport, catalog);
  private final LanguageSelectionController controller =
      new LanguageSelectionController(preferences, catalog, transport, menuService);

  @Test
  void sendStartChooserCreates2x2KeyboardWithStartCallbacks() throws Exception {
    controller.sendStartChooser(12345L);

    assertThat(transport.sentMessages).hasSize(1);
    var message = transport.sentMessages.get(0);
    assertThat(message.text()).isEqualTo(catalog.initialLanguagePrompt());
    assertThat(message.keyboard()).hasSize(2);

    List<TelegramInlineButton> row1 = message.keyboard().get(0);
    assertThat(row1).hasSize(2);
    assertThat(row1.get(0).text()).isEqualTo("🇬🇧 English");
    assertThat(row1.get(0).callbackData()).isEqualTo("l1:s:en");
    assertThat(row1.get(1).text()).isEqualTo("🇷🇺 Русский");
    assertThat(row1.get(1).callbackData()).isEqualTo("l1:s:ru");

    List<TelegramInlineButton> row2 = message.keyboard().get(1);
    assertThat(row2).hasSize(2);
    assertThat(row2.get(0).text()).isEqualTo("🇺🇦 Українська");
    assertThat(row2.get(0).callbackData()).isEqualTo("l1:s:uk");
    assertThat(row2.get(1).text()).isEqualTo("🇵🇱 Polski");
    assertThat(row2.get(1).callbackData()).isEqualTo("l1:s:pl");
  }

  @Test
  void sendLanguageChooserMarksCurrentLanguage() throws Exception {
    controller.sendLanguageChooser(12345L, TelegramLanguage.POLISH);

    assertThat(transport.sentMessages).hasSize(1);
    var message = transport.sentMessages.get(0);
    assertThat(message.keyboard()).hasSize(2);

    List<TelegramInlineButton> row1 = message.keyboard().get(0);
    assertThat(row1.get(0).text()).isEqualTo("🇬🇧 English");
    assertThat(row1.get(0).callbackData()).isEqualTo("l1:c:en");

    List<TelegramInlineButton> row2 = message.keyboard().get(1);
    assertThat(row2.get(1).text()).isEqualTo("✅ 🇵🇱 Polski");
    assertThat(row2.get(1).callbackData()).isEqualTo("l1:c:pl");
  }

  @Test
  void handleStartCallbackPersistsLanguageSynchronizesMenuAndEditsWithWelcome() {
    var callback = new LanguageCallback(LanguageCallback.Source.START, TelegramLanguage.UKRAINIAN);
    controller.handleCallback(callback, 42L, 12345L, 77, "query-1");

    assertThat(preferences.persistedLanguage).isEqualTo(TelegramLanguage.UKRAINIAN);
    assertThat(menuTransport.configuredChatIds).containsExactly(12345L);
    assertThat(menuTransport.chatCommands.get(0).getDescription())
        .isEqualTo("Почати / обрати мову");

    assertThat(transport.edits).hasSize(1);
    var edit = transport.edits.get(0);
    assertThat(edit.text()).isEqualTo(catalog.welcome(TelegramLanguage.UKRAINIAN));
    assertThat(edit.keyboard()).isEmpty();

    assertThat(transport.answers)
        .containsExactly("query-1:" + catalog.languageChanged(TelegramLanguage.UKRAINIAN));
  }

  @Test
  void handleCommandCallbackPersistsLanguageSynchronizesMenuAndEditsWithConfirmation() {
    var callback = new LanguageCallback(LanguageCallback.Source.COMMAND, TelegramLanguage.RUSSIAN);
    controller.handleCallback(callback, 42L, 12345L, 77, "query-2");

    assertThat(preferences.persistedLanguage).isEqualTo(TelegramLanguage.RUSSIAN);
    assertThat(menuTransport.configuredChatIds).containsExactly(12345L);

    assertThat(transport.edits).hasSize(1);
    var edit = transport.edits.get(0);
    assertThat(edit.text()).isEqualTo(catalog.languageChanged(TelegramLanguage.RUSSIAN));
    assertThat(edit.keyboard()).isEmpty();

    assertThat(transport.answers)
        .containsExactly("query-2:" + catalog.languageChanged(TelegramLanguage.RUSSIAN));
  }

  private static final class RecordingPreferences implements TelegramLanguagePreferences {
    private TelegramLanguage persistedLanguage = TelegramLanguage.ENGLISH;

    @Override
    public TelegramLanguage getLanguage(long appUserId) {
      return persistedLanguage;
    }

    @Override
    public void setLanguage(long appUserId, TelegramLanguage language) {
      this.persistedLanguage = language;
    }
  }

  private static final class RecordingInteractiveTransport implements TelegramInteractiveTransport {
    private final List<TelegramInteractiveMessage> sentMessages = new ArrayList<>();
    private final List<TelegramInteractiveMessage> edits = new ArrayList<>();
    private final List<String> answers = new ArrayList<>();

    @Override
    public void send(long privateChatId, TelegramInteractiveMessage message) {
      sentMessages.add(message);
    }

    @Override
    public void edit(long privateChatId, int messageId, TelegramInteractiveMessage message) {
      edits.add(message);
    }

    @Override
    public void answerCallback(String callbackQueryId, String text) {
      answers.add(callbackQueryId + ":" + text);
    }
  }

  private static final class RecordingMenuTransport implements TelegramCommandMenuTransport {
    private final List<Long> configuredChatIds = new ArrayList<>();
    private List<BotCommand> chatCommands = new ArrayList<>();

    @Override
    public void configureDefaultPrivateCommands(List<BotCommand> commands) {}

    @Override
    public void configureChatCommands(long privateChatId, List<BotCommand> commands) {
      configuredChatIds.add(privateChatId);
      this.chatCommands = List.copyOf(commands);
    }

    @Override
    public void configureChatMenuButton(long privateChatId) {}
  }
}
