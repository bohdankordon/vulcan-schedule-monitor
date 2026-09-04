package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

public record TelegramInlineButton(String text, String callbackData) {

  public TelegramInlineButton {
    if (text == null || text.isBlank() || callbackData == null || callbackData.isBlank()) {
      throw new IllegalArgumentException("Inline button text and callback data must be present");
    }
  }
}
