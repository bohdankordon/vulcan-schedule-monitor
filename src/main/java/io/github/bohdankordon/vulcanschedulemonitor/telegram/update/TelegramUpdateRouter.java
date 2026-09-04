package io.github.bohdankordon.vulcanschedulemonitor.telegram.update;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommand;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommandContext;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommandHandler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.command.TelegramCommandParser;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.ClassSelectionController;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.TelegramCallbackRouter;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.Update;

public final class TelegramUpdateRouter {

  private static final Logger LOGGER = LoggerFactory.getLogger(TelegramUpdateRouter.class);

  private final TelegramCommandParser parser;
  private final TelegramIdentityRegistration identities;
  private final TelegramMessageTransport transport;
  private final Map<TelegramCommand, TelegramCommandHandler> handlers;
  private final TelegramCallbackRouter callbackRouter;
  private final ClassSelectionController classes;

  public TelegramUpdateRouter(
      TelegramCommandParser parser,
      TelegramIdentityRegistration identities,
      TelegramMessageTransport transport,
      List<TelegramCommandHandler> handlers) {
    this(parser, identities, transport, handlers, null, null);
  }

  public TelegramUpdateRouter(
      TelegramCommandParser parser,
      TelegramIdentityRegistration identities,
      TelegramMessageTransport transport,
      List<TelegramCommandHandler> handlers,
      TelegramCallbackRouter callbackRouter,
      ClassSelectionController classes) {
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
    this.identities = Objects.requireNonNull(identities, "identities must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.callbackRouter = callbackRouter;
    this.classes = classes;
    this.handlers = new EnumMap<>(TelegramCommand.class);
    for (TelegramCommandHandler handler : handlers) {
      if (this.handlers.put(handler.supportedCommand(), handler) != null) {
        throw new IllegalArgumentException("Duplicate Telegram command handler");
      }
    }
  }

  public void route(Update update) {
    if (update == null) {
      return;
    }
    if (update.getCallbackQuery() != null) {
      if (callbackRouter != null) {
        callbackRouter.route(update.getCallbackQuery());
      }
      return;
    }
    if (!update.hasMessage()) {
      return;
    }
    var message = update.getMessage();
    var sender = message.getFrom();
    var chat = message.getChat();
    if (sender == null
        || chat == null
        || sender.getId() == null
        || chat.getId() == null
        || !Boolean.TRUE.equals(chat.isUserChat())
        || Boolean.TRUE.equals(sender.getIsBot())
        || !message.hasText()) {
      return;
    }
    var command = parser.parse(message.getText());
    if (command.isEmpty()) {
      return;
    }
    var user = identities.registerOrUpdate(sender.getId(), chat.getId());
    var context = new TelegramCommandContext(user.id(), chat.getId());
    if (command.orElseThrow() == TelegramCommand.CLASSES && classes != null) {
      try {
        classes.send(context.appUserId(), context.privateChatId(), 0);
        LOGGER.debug(
            "Telegram update processed: updateId={}, command=CLASSES", update.getUpdateId());
      } catch (TelegramTransportException failure) {
        LOGGER.warn(
            "Telegram class list reply failed: updateId={}, category={}",
            update.getUpdateId(),
            failure.category());
      }
      return;
    }
    if (!handlers.containsKey(command.orElseThrow())) {
      return;
    }
    String reply = handlers.get(command.orElseThrow()).handle(context);
    try {
      transport.sendPlainText(chat.getId(), reply);
      LOGGER.debug(
          "Telegram update processed: updateId={}, command={}",
          update.getUpdateId(),
          command.orElseThrow());
    } catch (TelegramTransportException failure) {
      LOGGER.warn(
          "Telegram command reply failed: updateId={}, command={}, category={}",
          update.getUpdateId(),
          command.orElseThrow(),
          failure.category());
    }
  }
}
