package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import java.util.List;

public record TelegramInteractiveMessage(String text, List<List<TelegramInlineButton>> keyboard) {

  public TelegramInteractiveMessage {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Interactive message text must be present");
    }
    keyboard = keyboard.stream().map(List::copyOf).toList();
  }
}
