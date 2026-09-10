package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RetryAfterParserTest {

  private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final RetryAfterParser parser = new RetryAfterParser(CLOCK);

  @Test
  void nullAndBlankYieldAbsentWithoutDelay() {
    RetryAfterParseResult nullResult = parser.parse(null);
    assertThat(nullResult.representation()).isEqualTo(RetryAfterRepresentation.ABSENT);
    assertThat(nullResult.delay()).isEmpty();

    RetryAfterParseResult emptyResult = parser.parse("");
    assertThat(emptyResult.representation()).isEqualTo(RetryAfterRepresentation.ABSENT);
    assertThat(emptyResult.delay()).isEmpty();

    RetryAfterParseResult blankResult = parser.parse("   \t  \n ");
    assertThat(blankResult.representation()).isEqualTo(RetryAfterRepresentation.ABSENT);
    assertThat(blankResult.delay()).isEmpty();
  }

  @Test
  void positiveDeltaSecondsYieldsDeltaSecondsRepresentation() {
    RetryAfterParseResult result = parser.parse("120");
    assertThat(result.representation()).isEqualTo(RetryAfterRepresentation.DELTA_SECONDS);
    assertThat(result.delay()).contains(Duration.ofSeconds(120));

    RetryAfterParseResult padded = parser.parse("  30  ");
    assertThat(padded.representation()).isEqualTo(RetryAfterRepresentation.DELTA_SECONDS);
    assertThat(padded.delay()).contains(Duration.ofSeconds(30));
  }

  @Test
  void zeroDeltaSecondsYieldsZeroDuration() {
    RetryAfterParseResult result = parser.parse("0");
    assertThat(result.representation()).isEqualTo(RetryAfterRepresentation.DELTA_SECONDS);
    assertThat(result.delay()).contains(Duration.ZERO);
  }

  @Test
  void negativeDeltaSecondsIsClassifiedAsMalformed() {
    RetryAfterParseResult result = parser.parse("-1");
    assertThat(result.representation()).isEqualTo(RetryAfterRepresentation.MALFORMED);
    assertThat(result.delay()).isEmpty();

    RetryAfterParseResult negativePadded = parser.parse(" -42 ");
    assertThat(negativePadded.representation()).isEqualTo(RetryAfterRepresentation.MALFORMED);
    assertThat(negativePadded.delay()).isEmpty();
  }

  @Test
  void validFutureHttpDateYieldsDurationToTarget() {
    String futureDate =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(NOW.plusSeconds(90), ZoneOffset.UTC));
    RetryAfterParseResult result = parser.parse(futureDate);

    assertThat(result.representation()).isEqualTo(RetryAfterRepresentation.HTTP_DATE);
    assertThat(result.delay()).contains(Duration.ofSeconds(90));
  }

  @Test
  void pastHttpDateYieldsZeroDuration() {
    String pastDate =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(NOW.minusSeconds(15), ZoneOffset.UTC));
    RetryAfterParseResult result = parser.parse(pastDate);

    assertThat(result.representation()).isEqualTo(RetryAfterRepresentation.HTTP_DATE);
    assertThat(result.delay()).contains(Duration.ZERO);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-a-number",
        "5.5",
        "100s",
        "Thu, 35 Sep 2026 12:00:00 GMT",
        "2026-09-10T12:00:00Z",
        "9999999999999999999999999999999999999999999999"
      })
  void unparseableValuesAreClassifiedAsMalformedWithoutDelay(String malformed) {
    RetryAfterParseResult result = parser.parse(malformed);
    assertThat(result.representation()).isEqualTo(RetryAfterRepresentation.MALFORMED);
    assertThat(result.delay()).isEmpty();
  }

  @Test
  void rawInputIsNotRetainedOrRenderedInToString() {
    String secretMarker = "SENSITIVE_RETRY_AFTER_VALUE_XYZ123";
    RetryAfterParseResult result = parser.parse(secretMarker);

    assertThat(result.representation()).isEqualTo(RetryAfterRepresentation.MALFORMED);
    assertThat(result.toString())
        .doesNotContain(secretMarker)
        .contains("RetryAfterParseResult[representation=MALFORMED]");
  }

  @Test
  void parseDurationConvenienceDelegatesToParse() {
    assertThat(parser.parseDuration(null)).isNull();
    assertThat(parser.parseDuration("10")).isEqualTo(Duration.ofSeconds(10));
    assertThat(parser.parseDuration("malformed")).isNull();
  }
}
