package io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

class TelegramCommandMenuServiceTest {

  private final TelegramTextCatalog catalog = new TelegramTextCatalog();
  private final RecordingMenuTransport transport = new RecordingMenuTransport();
  private final TelegramCommandMenuService service =
      new TelegramCommandMenuService(transport, catalog);

  @Test
  void exactCommandListAndDeterministicOrderAcrossAllLanguages() {
    List<String> expectedCommandNames =
        List.of("start", "connect", "classes", "subscriptions", "status", "language", "help");

    for (TelegramLanguage language : TelegramLanguage.values()) {
      List<BotCommand> commands = service.buildCommands(language);
      List<String> names = commands.stream().map(BotCommand::getCommand).toList();
      assertThat(names).isEqualTo(expectedCommandNames);
    }
  }

  @Test
  void localizedDescriptionsForEnglish() {
    List<BotCommand> commands = service.buildCommands(TelegramLanguage.ENGLISH);
    assertThat(commands.get(0).getDescription()).isEqualTo("Start / choose language");
    assertThat(commands.get(1).getDescription()).isEqualTo("Connect your VULCAN account");
    assertThat(commands.get(2).getDescription()).isEqualTo("Choose classes to monitor");
    assertThat(commands.get(3).getDescription()).isEqualTo("View monitored classes");
    assertThat(commands.get(4).getDescription())
        .isEqualTo("Check connection and monitoring status");
    assertThat(commands.get(5).getDescription()).isEqualTo("Change language");
    assertThat(commands.get(6).getDescription()).isEqualTo("Show all commands");
  }

  @Test
  void localizedDescriptionsForRussian() {
    List<BotCommand> commands = service.buildCommands(TelegramLanguage.RUSSIAN);
    assertThat(commands.get(0).getDescription()).isEqualTo("Начать / выбрать язык");
    assertThat(commands.get(1).getDescription()).isEqualTo("Подключить аккаунт VULCAN");
    assertThat(commands.get(2).getDescription()).isEqualTo("Выбрать классы для отслеживания");
    assertThat(commands.get(3).getDescription()).isEqualTo("Показать отслеживаемые классы");
    assertThat(commands.get(4).getDescription()).isEqualTo("Проверить состояние подключения");
    assertThat(commands.get(5).getDescription()).isEqualTo("Изменить язык");
    assertThat(commands.get(6).getDescription()).isEqualTo("Показать все команды");
  }

  @Test
  void localizedDescriptionsForUkrainian() {
    List<BotCommand> commands = service.buildCommands(TelegramLanguage.UKRAINIAN);
    assertThat(commands.get(0).getDescription()).isEqualTo("Почати / обрати мову");
    assertThat(commands.get(1).getDescription()).isEqualTo("Підключити обліковий запис VULCAN");
    assertThat(commands.get(2).getDescription()).isEqualTo("Обрати класи для відстеження");
    assertThat(commands.get(3).getDescription()).isEqualTo("Показати відстежувані класи");
    assertThat(commands.get(4).getDescription()).isEqualTo("Перевірити стан підключення");
    assertThat(commands.get(5).getDescription()).isEqualTo("Змінити мову");
    assertThat(commands.get(6).getDescription()).isEqualTo("Показати всі команди");
  }

  @Test
  void localizedDescriptionsForPolish() {
    List<BotCommand> commands = service.buildCommands(TelegramLanguage.POLISH);
    assertThat(commands.get(0).getDescription()).isEqualTo("Rozpocznij / wybierz język");
    assertThat(commands.get(1).getDescription()).isEqualTo("Połącz konto VULCAN");
    assertThat(commands.get(2).getDescription()).isEqualTo("Wybierz klasy do monitorowania");
    assertThat(commands.get(3).getDescription()).isEqualTo("Pokaż monitorowane klasy");
    assertThat(commands.get(4).getDescription()).isEqualTo("Sprawdź stan połączenia");
    assertThat(commands.get(5).getDescription()).isEqualTo("Zmień język");
    assertThat(commands.get(6).getDescription()).isEqualTo("Pokaż wszystkie polecenia");
  }

  @Test
  void configureDefaultMenuUsesEnglishForPrivateChats() {
    service.configureDefaultMenu();
    assertThat(transport.defaultCommands).hasSize(7);
    assertThat(transport.defaultCommands.get(0).getDescription())
        .isEqualTo("Start / choose language");
  }

  @Test
  void synchronizeChatMenuConfiguresChatScopeAndCommandsButton() {
    service.synchronizeChatMenu(12345L, TelegramLanguage.POLISH);

    assertThat(transport.configuredChatIds).containsExactly(12345L);
    assertThat(transport.chatCommands).hasSize(7);
    assertThat(transport.chatCommands.get(0).getDescription())
        .isEqualTo("Rozpocznij / wybierz język");
    assertThat(transport.menuButtonChatIds).containsExactly(12345L);
  }

  @Test
  void transportFailureDoesNotThrowOrPropagateException() {
    transport.failOnConfigure = true;

    assertThatCode(() -> service.configureDefaultMenu()).doesNotThrowAnyException();
    assertThatCode(() -> service.synchronizeChatMenu(999L, TelegramLanguage.UKRAINIAN))
        .doesNotThrowAnyException();
  }

  private static final class RecordingMenuTransport implements TelegramCommandMenuTransport {
    private List<BotCommand> defaultCommands = new ArrayList<>();
    private List<BotCommand> chatCommands = new ArrayList<>();
    private final List<Long> configuredChatIds = new ArrayList<>();
    private final List<Long> menuButtonChatIds = new ArrayList<>();
    private boolean failOnConfigure = false;

    @Override
    public void configureDefaultPrivateCommands(List<BotCommand> commands)
        throws TelegramTransportException {
      if (failOnConfigure) {
        throw new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null);
      }
      this.defaultCommands = List.copyOf(commands);
    }

    @Override
    public void configureChatCommands(long privateChatId, List<BotCommand> commands)
        throws TelegramTransportException {
      if (failOnConfigure) {
        throw new TelegramTransportException(TelegramFailureCategory.RATE_LIMITED, null);
      }
      configuredChatIds.add(privateChatId);
      this.chatCommands = List.copyOf(commands);
    }

    @Override
    public void configureChatMenuButton(long privateChatId) throws TelegramTransportException {
      if (failOnConfigure) {
        throw new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null);
      }
      menuButtonChatIds.add(privateChatId);
    }
  }
}
