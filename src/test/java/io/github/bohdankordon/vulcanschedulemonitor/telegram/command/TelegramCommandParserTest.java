package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelegramCommandParserTest {

  private final TelegramCommandParser parser = new TelegramCommandParser();

  @Test
  void parsesSupportedCommandsAndBotSuffixesWithoutRetainingArguments() {
    assertThat(parser.parse("/start")).contains(TelegramCommand.START);
    assertThat(parser.parse("/start@somebot")).contains(TelegramCommand.START);
    assertThat(parser.parse("  /StAtUs  ")).contains(TelegramCommand.STATUS);
    assertThat(parser.parse("/connect private-looking-argument")).contains(TelegramCommand.CONNECT);
  }

  @Test
  void safelyIgnoresUnknownPlainAndEmptyText() {
    assertThat(parser.parse("/subscribe 42")).isEmpty();
    assertThat(parser.parse("ordinary text")).isEmpty();
    assertThat(parser.parse("  ")).isEmpty();
    assertThat(parser.parse(null)).isEmpty();
  }
}
