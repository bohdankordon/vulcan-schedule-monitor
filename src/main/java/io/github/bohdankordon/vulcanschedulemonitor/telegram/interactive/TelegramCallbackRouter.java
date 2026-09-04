package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

public final class TelegramCallbackRouter {

  private static final Logger LOGGER = LoggerFactory.getLogger(TelegramCallbackRouter.class);

  private final ClassSelectionCallbackParser parser;
  private final TelegramIdentityRegistration identities;
  private final MonitoringSubscriptionService subscriptions;
  private final ClassSelectionController classes;
  private final TelegramInteractiveTransport transport;

  public TelegramCallbackRouter(
      ClassSelectionCallbackParser parser,
      TelegramIdentityRegistration identities,
      MonitoringSubscriptionService subscriptions,
      ClassSelectionController classes,
      TelegramInteractiveTransport transport) {
    this.parser = Objects.requireNonNull(parser, "parser must not be null");
    this.identities = Objects.requireNonNull(identities, "identities must not be null");
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
    this.classes = Objects.requireNonNull(classes, "classes must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
  }

  public void route(CallbackQuery query) {
    if (!isPrivateHumanCallback(query)) {
      return;
    }
    var parsed = parser.parse(query.getData());
    if (parsed.isEmpty()) {
      answer(query.getId(), "This class control is no longer valid.");
      return;
    }

    long telegramUserId = query.getFrom().getId();
    long privateChatId = query.getMessage().getChatId();
    int messageId = query.getMessage().getMessageId();
    long appUserId = identities.registerOrUpdate(telegramUserId, privateChatId).id();
    ClassSelectionCallback callback = parsed.orElseThrow();
    String acknowledgment = "Class list refreshed.";
    if (callback.action() == ClassSelectionCallback.Action.TOGGLE) {
      try {
        if (subscriptions.isSubscribed(appUserId, callback.catalogClassId())) {
          subscriptions.disable(appUserId, callback.catalogClassId());
          acknowledgment = "Monitoring disabled.";
        } else {
          subscriptions.enable(appUserId, callback.catalogClassId());
          acknowledgment = "Monitoring enabled.";
        }
      } catch (IllegalArgumentException rejected) {
        acknowledgment = "That class is not available.";
      }
    }

    try {
      classes.edit(appUserId, privateChatId, messageId, callback.page());
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
