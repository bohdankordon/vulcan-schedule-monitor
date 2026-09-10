package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Parses the two standard Retry-After representations without retaining the raw value. */
public final class RetryAfterParser {

  private final Clock clock;

  public RetryAfterParser(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public RetryAfterParseResult parse(String value) {
    if (value == null || value.isBlank()) {
      return RetryAfterParseResult.absent();
    }
    String candidate = value.trim();
    try {
      long seconds = Long.parseLong(candidate);
      return seconds < 0
          ? RetryAfterParseResult.malformed()
          : RetryAfterParseResult.deltaSeconds(Duration.ofSeconds(seconds));
    } catch (NumberFormatException ignored) {
      // Try the HTTP-date form next.
    }
    try {
      Instant retryAt =
          ZonedDateTime.parse(candidate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      Duration delay = Duration.between(clock.instant(), retryAt);
      return RetryAfterParseResult.httpDate(delay.isNegative() ? Duration.ZERO : delay);
    } catch (DateTimeParseException | ArithmeticException ignored) {
      return RetryAfterParseResult.malformed();
    }
  }

  public Duration parseDuration(String value) {
    return parse(value).duration();
  }
}
