package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ClassSelectionCallbackParserTest {

  private final ClassSelectionCallbackParser parser = new ClassSelectionCallbackParser();

  @Test
  void parsesVersionedToggleAndPageCallbacks() {
    assertThat(parser.parse("c1:t:42:3"))
        .contains(new ClassSelectionCallback(ClassSelectionCallback.Action.TOGGLE, 42L, 3));
    assertThat(parser.parse("c1:p:2"))
        .contains(new ClassSelectionCallback(ClassSelectionCallback.Action.PAGE, null, 2));
  }

  @ParameterizedTest
  @MethodSource("invalidPayloads")
  void rejectsMalformedUnboundedOrUnknownPayloads(String payload) {
    assertThat(parser.parse(payload)).isEmpty();
  }

  private static Stream<String> invalidPayloads() {
    return Stream.of(
        "",
        "c2:t:42:0",
        "c1:t:not-a-number:0",
        "c1:t:0:0",
        "c1:t:-1:0",
        "c1:t:42:-1",
        "c1:t:42:100001",
        "c1:x:42:0",
        "c1:p:0:garbage",
        "c1:t:42:0:garbage",
        "c1:" + "x".repeat(65));
  }
}
