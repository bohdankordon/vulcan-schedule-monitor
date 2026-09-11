package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringClassSelection;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.TelegramLanguage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.i18n.TelegramTextCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatusService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ClassSelectionController {

  static final int PAGE_SIZE = 8;

  private final MonitoringSubscriptionService subscriptions;
  private final VulcanConnectionStatusService connections;
  private final TelegramTextCatalog textCatalog;
  private final TelegramMessageTransport plainTransport;
  private final TelegramInteractiveTransport interactiveTransport;

  public ClassSelectionController(
      MonitoringSubscriptionService subscriptions,
      VulcanConnectionStatusService connections,
      TelegramMessageTransport plainTransport,
      TelegramInteractiveTransport interactiveTransport) {
    this(
        subscriptions,
        connections,
        new TelegramTextCatalog(),
        plainTransport,
        interactiveTransport);
  }

  public ClassSelectionController(
      MonitoringSubscriptionService subscriptions,
      VulcanConnectionStatusService connections,
      TelegramTextCatalog textCatalog,
      TelegramMessageTransport plainTransport,
      TelegramInteractiveTransport interactiveTransport) {
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions must not be null");
    this.connections = Objects.requireNonNull(connections, "connections must not be null");
    this.textCatalog = Objects.requireNonNull(textCatalog, "textCatalog must not be null");
    this.plainTransport = Objects.requireNonNull(plainTransport, "plainTransport must not be null");
    this.interactiveTransport =
        Objects.requireNonNull(interactiveTransport, "interactiveTransport must not be null");
  }

  public void send(long appUserId, long privateChatId, int requestedPage)
      throws TelegramTransportException {
    send(appUserId, privateChatId, requestedPage, TelegramLanguage.ENGLISH);
  }

  public void send(long appUserId, long privateChatId, int requestedPage, TelegramLanguage language)
      throws TelegramTransportException {
    var message = render(appUserId, requestedPage, language);
    if (message.keyboard().isEmpty()) {
      plainTransport.sendPlainText(privateChatId, message.text());
    } else {
      interactiveTransport.send(privateChatId, message);
    }
  }

  public void edit(long appUserId, long privateChatId, int messageId, int requestedPage)
      throws TelegramTransportException {
    edit(appUserId, privateChatId, messageId, requestedPage, TelegramLanguage.ENGLISH);
  }

  public void edit(
      long appUserId,
      long privateChatId,
      int messageId,
      int requestedPage,
      TelegramLanguage language)
      throws TelegramTransportException {
    interactiveTransport.edit(privateChatId, messageId, render(appUserId, requestedPage, language));
  }

  private TelegramInteractiveMessage render(
      long appUserId, int requestedPage, TelegramLanguage language) {
    Objects.requireNonNull(language, "language must not be null");
    VulcanConnectionStatus status = connections.statusForUser(appUserId);
    if (status.state() == VulcanConnectionStatus.State.NOT_CONNECTED) {
      return new TelegramInteractiveMessage(
          textCatalog.classSelectionNotConnected(language), List.of());
    }
    if (status.state() == VulcanConnectionStatus.State.RECONNECT_REQUIRED) {
      return new TelegramInteractiveMessage(
          textCatalog.classSelectionReconnectRequired(language), List.of());
    }

    List<MonitoringClassSelection> classes = subscriptions.availableClasses(appUserId);
    if (classes.isEmpty()) {
      return new TelegramInteractiveMessage(textCatalog.classSelectionEmpty(language), List.of());
    }

    int pageCount = (classes.size() + PAGE_SIZE - 1) / PAGE_SIZE;
    int page = Math.min(Math.max(requestedPage, 0), pageCount - 1);
    int start = page * PAGE_SIZE;
    int end = Math.min(start + PAGE_SIZE, classes.size());
    List<List<TelegramInlineButton>> keyboard = new ArrayList<>();
    for (MonitoringClassSelection selection : classes.subList(start, end)) {
      String marker = selection.subscribed() ? "✅ " : "⬜ ";
      keyboard.add(
          List.of(
              new TelegramInlineButton(
                  marker + selection.className(),
                  "c1:t:" + selection.catalogClassId() + ":" + page)));
    }
    List<TelegramInlineButton> navigation = new ArrayList<>();
    if (page > 0) {
      navigation.add(
          new TelegramInlineButton(textCatalog.previous(language), "c1:p:" + (page - 1)));
    }
    if (page + 1 < pageCount) {
      navigation.add(new TelegramInlineButton(textCatalog.next(language), "c1:p:" + (page + 1)));
    }
    if (!navigation.isEmpty()) {
      keyboard.add(List.copyOf(navigation));
    }
    return new TelegramInteractiveMessage(
        textCatalog.classSelectionHeader(language, page, pageCount), keyboard);
  }
}
