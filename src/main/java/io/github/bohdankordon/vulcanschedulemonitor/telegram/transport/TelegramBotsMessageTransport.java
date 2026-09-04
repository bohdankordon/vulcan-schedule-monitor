package io.github.bohdankordon.vulcanschedulemonitor.telegram.transport;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.TelegramInteractiveMessage;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive.TelegramInteractiveTransport;
import java.util.Objects;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

public final class TelegramBotsMessageTransport
    implements TelegramMessageTransport, TelegramInteractiveTransport, AutoCloseable {

  private final OkHttpClient httpClient;
  private final TelegramClient telegramClient;
  private final TelegramApiFailureClassifier classifier;
  private final TelegramProviderAvailabilityGate gate;

  public TelegramBotsMessageTransport(String token, TelegramProviderAvailabilityGate gate) {
    this(new OkHttpClient(), token, gate, new TelegramApiFailureClassifier());
  }

  TelegramBotsMessageTransport(
      OkHttpClient httpClient,
      String token,
      TelegramProviderAvailabilityGate gate,
      TelegramApiFailureClassifier classifier) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    this.telegramClient = new OkHttpTelegramClient(httpClient, token);
    this.gate = Objects.requireNonNull(gate, "gate must not be null");
    this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
  }

  @Override
  public void sendPlainText(long privateChatId, String text) throws TelegramTransportException {
    execute(SendMessage.builder().chatId(privateChatId).text(text).build());
  }

  @Override
  public void send(long privateChatId, TelegramInteractiveMessage message)
      throws TelegramTransportException {
    execute(
        SendMessage.builder()
            .chatId(privateChatId)
            .text(message.text())
            .replyMarkup(keyboard(message))
            .build());
  }

  @Override
  public void edit(long privateChatId, int messageId, TelegramInteractiveMessage message)
      throws TelegramTransportException {
    execute(
        EditMessageText.builder()
            .chatId(privateChatId)
            .messageId(messageId)
            .text(message.text())
            .replyMarkup(keyboard(message))
            .build());
  }

  @Override
  public void answerCallback(String callbackQueryId, String text)
      throws TelegramTransportException {
    execute(AnswerCallbackQuery.builder().callbackQueryId(callbackQueryId).text(text).build());
  }

  private void execute(
      org.telegram.telegrambots.meta.api.methods.botapimethods.BotApiMethod<?> method)
      throws TelegramTransportException {
    if (!gate.isAvailable()) {
      throw new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null);
    }
    try {
      telegramClient.execute(method);
    } catch (TelegramApiException exception) {
      TelegramTransportException failure = classifier.classify(exception);
      if (failure.category() == TelegramFailureCategory.RATE_LIMITED) {
        gate.defer(failure.retryAfter().orElseThrow());
      } else if (failure.category() == TelegramFailureCategory.AUTHENTICATION) {
        gate.suspendUntilRestart();
      }
      throw failure;
    }
  }

  private static InlineKeyboardMarkup keyboard(TelegramInteractiveMessage message) {
    var rows =
        message.keyboard().stream()
            .map(
                row ->
                    new InlineKeyboardRow(
                        row.stream()
                            .map(
                                button ->
                                    InlineKeyboardButton.builder()
                                        .text(button.text())
                                        .callbackData(button.callbackData())
                                        .build())
                            .toList()))
            .toList();
    return InlineKeyboardMarkup.builder().keyboard(rows).build();
  }

  @Override
  public void close() {
    httpClient.dispatcher().executorService().shutdown();
    httpClient.connectionPool().evictAll();
    if (httpClient.cache() != null) {
      try {
        httpClient.cache().close();
      } catch (java.io.IOException ignored) {
        // Best-effort cleanup of an optional cache.
      }
    }
  }
}
