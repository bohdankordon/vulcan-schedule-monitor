package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RetryAfterParseResult;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RetryAfterParser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class EffectiveDelayBucketTest {

  @Test
  void nullAndNegativeDurationsYieldZeroBucket() {
    assertThat(EffectiveDelayBucket.fromDuration(null)).isEqualTo(EffectiveDelayBucket.ZERO);
    assertThat(EffectiveDelayBucket.fromDuration(Duration.ofSeconds(-1)))
        .isEqualTo(EffectiveDelayBucket.ZERO);
    assertThat(EffectiveDelayBucket.fromDuration(Duration.ofNanos(-1)))
        .isEqualTo(EffectiveDelayBucket.ZERO);
  }

  @ParameterizedTest
  @CsvSource({
    "PT0S, ZERO",
    "PT0.000000001S, LE_10_SECONDS",
    "PT10S, LE_10_SECONDS",
    "PT10.000000001S, LE_30_SECONDS",
    "PT30S, LE_30_SECONDS",
    "PT30.000000001S, LE_60_SECONDS",
    "PT60S, LE_60_SECONDS",
    "PT60.000000001S, LE_5_MINUTES",
    "PT300S, LE_5_MINUTES",
    "PT300.000000001S, GT_5_MINUTES"
  })
  void exactBoundaryClassification(String durationIso, EffectiveDelayBucket expectedBucket) {
    Duration duration = Duration.parse(durationIso);
    assertThat(EffectiveDelayBucket.fromDuration(duration)).isEqualTo(expectedBucket);
  }

  @Test
  void httpDateWithSubsecondClockEnsuresBucketAndDecisionCannotDisagree() {
    // Clock contains fractional seconds: 12:00:00.500 UTC
    Instant clockInstant = Instant.parse("2026-09-10T12:00:00.500Z");
    Clock fractionalClock = Clock.fixed(clockInstant, ZoneOffset.UTC);

    // VULCAN returns HTTP-date with second-precision: 12:00:11 UTC (11 seconds after 12:00:00)
    // The exact duration between 12:00:00.500 and 12:00:11 is 10.5 seconds
    String httpDateHeader =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(Instant.parse("2026-09-10T12:00:11.000Z"), ZoneOffset.UTC));

    RetryAfterParser parser = new RetryAfterParser(fractionalClock);
    RetryAfterParseResult parseResult = parser.parse(httpDateHeader);
    Duration effectiveDelay = parseResult.delay().orElseThrow();

    assertThat(effectiveDelay).isEqualTo(Duration.ofMillis(10500));

    // Decision logic from ResilientWeeklyScheduleSource:
    Duration maximumInlineRateLimitDelay = Duration.ofSeconds(10);
    ResilienceDecision decision =
        effectiveDelay.compareTo(maximumInlineRateLimitDelay) > 0
            ? ResilienceDecision.DEFERRED_GATE
            : ResilienceDecision.INLINE_RETRY;

    // Bucket from EffectiveDelayBucket:
    EffectiveDelayBucket bucket = EffectiveDelayBucket.fromDuration(effectiveDelay);

    // Both must agree that 10.5s is strictly greater than 10s:
    assertThat(decision).isEqualTo(ResilienceDecision.DEFERRED_GATE);
    assertThat(bucket).isEqualTo(EffectiveDelayBucket.LE_30_SECONDS);
  }

  @Test
  void exactTenSecondsAgreesOnInlineRetryAndLe10Bucket() {
    Instant clockInstant = Instant.parse("2026-09-10T12:00:00.000Z");
    Clock clock = Clock.fixed(clockInstant, ZoneOffset.UTC);

    String httpDateHeader =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(Instant.parse("2026-09-10T12:00:10.000Z"), ZoneOffset.UTC));

    RetryAfterParser parser = new RetryAfterParser(clock);
    RetryAfterParseResult parseResult = parser.parse(httpDateHeader);
    Duration effectiveDelay = parseResult.delay().orElseThrow();

    assertThat(effectiveDelay).isEqualTo(Duration.ofSeconds(10));

    Duration maximumInlineRateLimitDelay = Duration.ofSeconds(10);
    ResilienceDecision decision =
        effectiveDelay.compareTo(maximumInlineRateLimitDelay) > 0
            ? ResilienceDecision.DEFERRED_GATE
            : ResilienceDecision.INLINE_RETRY;

    EffectiveDelayBucket bucket = EffectiveDelayBucket.fromDuration(effectiveDelay);

    assertThat(decision).isEqualTo(ResilienceDecision.INLINE_RETRY);
    assertThat(bucket).isEqualTo(EffectiveDelayBucket.LE_10_SECONDS);
  }
}
