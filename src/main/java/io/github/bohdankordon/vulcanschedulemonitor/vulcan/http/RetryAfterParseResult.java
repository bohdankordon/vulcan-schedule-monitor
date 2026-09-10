package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Finite representation of a parsed Retry-After header. Deliberately never retains the raw input.
 */
public record RetryAfterParseResult(RetryAfterRepresentation representation, Duration duration) {

  public RetryAfterParseResult {
    Objects.requireNonNull(representation, "representation must not be null");
    switch (representation) {
      case ABSENT, MALFORMED -> {
        if (duration != null) {
          throw new IllegalArgumentException(representation + " must not have a duration");
        }
      }
      case DELTA_SECONDS, HTTP_DATE -> {
        Objects.requireNonNull(duration, representation + " requires non-null duration");
        if (duration.isNegative()) {
          throw new IllegalArgumentException(representation + " duration must not be negative");
        }
      }
    }
  }

  public static RetryAfterParseResult absent() {
    return new RetryAfterParseResult(RetryAfterRepresentation.ABSENT, null);
  }

  public static RetryAfterParseResult deltaSeconds(Duration delay) {
    return new RetryAfterParseResult(RetryAfterRepresentation.DELTA_SECONDS, delay);
  }

  public static RetryAfterParseResult httpDate(Duration delay) {
    return new RetryAfterParseResult(RetryAfterRepresentation.HTTP_DATE, delay);
  }

  public static RetryAfterParseResult malformed() {
    return new RetryAfterParseResult(RetryAfterRepresentation.MALFORMED, null);
  }

  public Optional<Duration> delay() {
    return Optional.ofNullable(duration);
  }

  @Override
  public String toString() {
    return "RetryAfterParseResult[representation="
        + representation
        + (duration != null ? ", delay=" + duration : "")
        + "]";
  }
}
