package io.github.bohdankordon.vulcanschedulemonitor.telegram.command.menu;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import java.util.List;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;

public interface TelegramCommandMenuTransport {

  void configureDefaultPrivateCommands(List<BotCommand> commands) throws TelegramTransportException;

  void configureChatCommands(long privateChatId, List<BotCommand> commands)
      throws TelegramTransportException;

  void configureChatMenuButton(long privateChatId) throws TelegramTransportException;
}
