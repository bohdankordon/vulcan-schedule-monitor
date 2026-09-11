package io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommand;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

public class TelegramCommandMenuService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TelegramCommandMenuService.class);

  public static final List<TelegramCommand> NATIVE_COMMAND_ORDER =
      List.of(
          TelegramCommand.START,
          TelegramCommand.CONNECT,
          TelegramCommand.CLASSES,
          TelegramCommand.SUBSCRIPTIONS,
          TelegramCommand.STATUS,
          TelegramCommand.LANGUAGE,
          TelegramCommand.HELP);

  private final TelegramCommandMenuTransport transport;
  private final TelegramTextCatalog textCatalog;

  public TelegramCommandMenuService(
      TelegramCommandMenuTransport transport, TelegramTextCatalog textCatalog) {
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
  }

  public void configureDefaultMenu() {
    try {
      transport.configureDefaultPrivateCommands(buildCommands(TelegramLanguage.ENGLISH));
      LOGGER.info("Default private chat command menu synchronized");
    } catch (TelegramTransportException failure) {
      LOGGER.warn(
          "Telegram default command menu synchronization failed: category={}", failure.category());
    } catch (Exception failure) {
      LOGGER.warn("Telegram default command menu synchronization failed: unexpected error");
    }
  }

  public void synchronizeChatMenu(long privateChatId, TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    try {
      transport.configureChatCommands(privateChatId, buildCommands(language));
      transport.configureChatMenuButton(privateChatId);
      LOGGER.debug("Telegram chat command menu synchronized");
    } catch (TelegramTransportException failure) {
      LOGGER.warn(
          "Telegram chat command menu synchronization failed: category={}", failure.category());
    } catch (Exception failure) {
      LOGGER.warn("Telegram chat command menu synchronization failed: unexpected error");
    }
  }

  public List<BotCommand> buildCommands(TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    return NATIVE_COMMAND_ORDER.stream()
        .map(
            command ->
                new BotCommand(
                    command.name().toLowerCase(Locale.ROOT),
                    textCatalog.nativeCommandDescription(language, command)))
        .toList();
  }
}
