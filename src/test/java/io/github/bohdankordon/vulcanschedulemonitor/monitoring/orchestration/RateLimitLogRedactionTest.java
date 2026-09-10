package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.ContentFamily;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitHeaderPresence;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitResponseObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitedOperation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RequestShapeObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RetryAfterParseResult;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RetryAfterParser;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.SetCookieCount;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionCookieCountBucket;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionCookieMutationObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanCookieMaterial;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RateLimitLogRedactionTest {

  private static final String SECRET_PORTAL_URL = "https://sensitive.vulcan.invalid/tenant/portal";
  private static final String SECRET_REFERER = "https://sensitive.vulcan.invalid/tenant/referer";
  private static final String SECRET_TOKEN = "TOP_SECRET_VERIFICATION_TOKEN_987";
  private static final String SECRET_GUID = "TOP_SECRET_APPGUID_654";
  private static final String SECRET_COOKIE_NAME = "SECRET_COOKIE_NAME_XYZ";
  private static final String SECRET_COOKIE_VALUE = "SECRET_COOKIE_VALUE_ABC";
  private static final String SECRET_MALFORMED_RETRY_AFTER = "SECRET_RAW_RETRY_AFTER_HEADER_VAL";
  private static final String SECRET_FORM_DATA = "SECRET_REQUEST_BODY_FORM_PAYLOAD";

  private static final List<String> ALL_SECRET_MARKERS =
      List.of(
          SECRET_PORTAL_URL,
          SECRET_REFERER,
          SECRET_TOKEN,
          SECRET_GUID,
          SECRET_COOKIE_NAME,
          SECRET_COOKIE_VALUE,
          SECRET_MALFORMED_RETRY_AFTER,
          SECRET_FORM_DATA);

  private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Test
  void formatterNeverRevealsAnySecretMarker() {
    RetryAfterParser parser = new RetryAfterParser(CLOCK);
    RetryAfterParseResult parseResult = parser.parse(SECRET_MALFORMED_RETRY_AFTER);

    URI appUri = URI.create("https://synthetic.invalid/app/");
    VulcanSessionMaterial materialBefore =
        VulcanSessionMaterial.structured(
            appUri,
            appUri,
            SECRET_TOKEN,
            SECRET_GUID,
            List.of(
                new VulcanCookieMaterial(
                    SECRET_COOKIE_NAME, SECRET_COOKIE_VALUE, "/app/", null, true, true)));
    VulcanSessionMaterial materialAfter =
        VulcanSessionMaterial.structured(
            appUri,
            appUri,
            SECRET_TOKEN,
            SECRET_GUID,
            List.of(
                new VulcanCookieMaterial(
                    SECRET_COOKIE_NAME, "NEW_SECRET_VAL", "/app/", null, true, true)));

    SessionCookieMutationObservation cookieMutation =
        SessionCookieMutationObservation.compare(materialBefore, materialAfter);

    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            "GetPlanLekcjiContext",
            429,
            ContentFamily.JSON,
            parseResult,
            SetCookieCount.ONE,
            new RateLimitHeaderPresence(false, false, false, false, false, false),
            new RequestShapeObservation(
                RequestShapeObservation.RequestMethodShape.POST,
                RequestShapeObservation.RequestContentTypeShape.FORM_URLENCODED,
                true,
                true,
                true,
                true,
                true),
            cookieMutation);

    String formatted =
        VulcanRateLimitLogFormatter.format(
            observation,
            "GetPlanLekcjiContext",
            DelaySource.HEADER,
            EffectiveDelayBucket.LE_10_SECONDS,
            ResilienceDecision.INLINE_RETRY,
            1,
            3);

    for (String secret : ALL_SECRET_MARKERS) {
      assertThat(formatted).doesNotContain(secret);
    }
    assertThat(formatted).doesNotContain("NEW_SECRET_VAL");

    assertThat(formatted)
        .startsWith("VULCAN schedule rate limited: operation=GetPlanLekcjiContext");
    assertThat(formatted).contains("status=429");
    assertThat(formatted).contains("content=JSON");
    assertThat(formatted).contains("retryAfter=MALFORMED");
    assertThat(formatted).contains("cookieMaterialChanged=true");
    assertThat(formatted).contains("rateLimitLimitPresent=false");
    assertThat(formatted).contains("rateLimitRemainingPresent=false");
    assertThat(formatted).contains("rateLimitResetPresent=false");
    assertThat(formatted).contains("xRateLimitLimitPresent=false");
    assertThat(formatted).contains("xRateLimitRemainingPresent=false");
    assertThat(formatted).contains("xRateLimitResetPresent=false");
  }

  @Test
  void toStringImplementationsNeverLeakSecrets() {
    RetryAfterParser parser = new RetryAfterParser(CLOCK);
    RetryAfterParseResult parseResult = parser.parse(SECRET_MALFORMED_RETRY_AFTER);
    assertThat(parseResult.toString()).doesNotContain(SECRET_MALFORMED_RETRY_AFTER);

    SessionCookieMutationObservation mutation =
        new SessionCookieMutationObservation(
            SessionCookieCountBucket.ONE, SessionCookieCountBucket.TWO, true, 1, 0, 1);
    assertThat(mutation.toString()).doesNotContain(SECRET_COOKIE_NAME);
    assertThat(mutation.toString()).doesNotContain(SECRET_COOKIE_VALUE);

    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            "GetPlanLekcjiContext",
            429,
            ContentFamily.JSON,
            parseResult,
            SetCookieCount.ONE,
            new RateLimitHeaderPresence(false, false, false, false, false, false),
            new RequestShapeObservation(
                RequestShapeObservation.RequestMethodShape.POST,
                RequestShapeObservation.RequestContentTypeShape.FORM_URLENCODED,
                true,
                true,
                true,
                true,
                true),
            mutation);
    assertThat(observation.toString()).doesNotContain(SECRET_COOKIE_NAME);
    assertThat(observation.toString()).doesNotContain(SECRET_COOKIE_VALUE);
    assertThat(observation.toString()).doesNotContain(SECRET_MALFORMED_RETRY_AFTER);

    VulcanHttpException exception =
        VulcanHttpException.rateLimited("GetPlanLekcjiContext", observation);
    assertThat(exception.getMessage()).doesNotContain(SECRET_COOKIE_NAME);
    assertThat(exception.getMessage()).doesNotContain(SECRET_COOKIE_VALUE);
    assertThat(exception.getMessage()).doesNotContain(SECRET_MALFORMED_RETRY_AFTER);
    assertThat(exception.toString()).doesNotContain(SECRET_COOKIE_NAME);
  }

  @Test
  void realResilientSourceLogOutputIsSanitizedAndExcludesAllSecrets(CapturedOutput output) {
    RetryAfterParser parser = new RetryAfterParser(CLOCK);
    RetryAfterParseResult parseResult = parser.parse(SECRET_MALFORMED_RETRY_AFTER);

    SessionCookieMutationObservation mutation =
        new SessionCookieMutationObservation(
            SessionCookieCountBucket.SIX, SessionCookieCountBucket.SIX, false, 0, 0, 0);

    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            "GetPlanLekcjiContext",
            429,
            ContentFamily.JSON,
            parseResult,
            SetCookieCount.ONE,
            new RateLimitHeaderPresence(false, false, false, false, false, false),
            new RequestShapeObservation(
                RequestShapeObservation.RequestMethodShape.POST,
                RequestShapeObservation.RequestContentTypeShape.FORM_URLENCODED,
                true,
                true,
                true,
                true,
                true),
            mutation);

    VulcanHttpException rateLimitedException =
        VulcanHttpException.rateLimited("GetPlanLekcjiContext", observation);

    RateLimitBackoffGate gate = new RateLimitBackoffGate(CLOCK);
    ResilientWeeklyScheduleSource source =
        new ResilientWeeklyScheduleSource(
            scope -> {
              throw rateLimitedException;
            },
            delay -> {},
            gate,
            1,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));

    TrackingScope scope =
        new TrackingScope(1L, 2L, 3L, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13));

    assertThatThrownBy(() -> source.fetchCompleteWeeklySnapshot(scope))
        .isInstanceOf(ScheduleSourceException.class);

    String logged = output.getAll();
    assertThat(logged).contains("VULCAN schedule rate limited: operation=GetPlanLekcjiContext");
    for (String secret : ALL_SECRET_MARKERS) {
      assertThat(logged).doesNotContain(secret);
    }
  }

  @Test
  void hostileOperationMarkerIsMappedToOtherAndNeverLeaked() {
    String hostileOperation = "SECRET_OPERATION_MARKER\nFAKE_LOG_LINE";

    // Test 1: Fallback path (observation == null)
    String fallbackFormatted =
        VulcanRateLimitLogFormatter.format(
            null,
            hostileOperation,
            DelaySource.FALLBACK,
            EffectiveDelayBucket.LE_30_SECONDS,
            ResilienceDecision.DEFERRED_GATE,
            1,
            3);

    assertThat(fallbackFormatted).contains("operation=OTHER");
    assertThat(fallbackFormatted).doesNotContain("SECRET_OPERATION_MARKER");
    assertThat(fallbackFormatted).doesNotContain("FAKE_LOG_LINE");
    assertThat(fallbackFormatted).doesNotContain("\n");
    assertThat(fallbackFormatted).doesNotContain("\r");

    // Test 2: Normal observation path with hostile string mapped safely
    RetryAfterParseResult parseResult = RetryAfterParseResult.absent();
    SessionCookieMutationObservation mutation =
        new SessionCookieMutationObservation(
            SessionCookieCountBucket.ZERO, SessionCookieCountBucket.ZERO, false, 0, 0, 0);
    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            hostileOperation,
            429,
            ContentFamily.JSON,
            parseResult,
            SetCookieCount.ZERO,
            new RateLimitHeaderPresence(false, false, false, false, false, false),
            new RequestShapeObservation(
                RequestShapeObservation.RequestMethodShape.POST,
                RequestShapeObservation.RequestContentTypeShape.FORM_URLENCODED,
                true,
                true,
                true,
                true,
                true),
            mutation);

    assertThat(observation.operation()).isEqualTo(RateLimitedOperation.OTHER);
    assertThat(observation.toString()).contains("operation=OTHER");
    assertThat(observation.toString()).doesNotContain("SECRET_OPERATION_MARKER");
    assertThat(observation.toString()).doesNotContain("FAKE_LOG_LINE");
    assertThat(observation.toString()).doesNotContain("\n");

    String observationFormatted =
        VulcanRateLimitLogFormatter.format(
            observation,
            hostileOperation,
            DelaySource.FALLBACK,
            EffectiveDelayBucket.LE_30_SECONDS,
            ResilienceDecision.DEFERRED_GATE,
            1,
            3);

    assertThat(observationFormatted).contains("operation=OTHER");
    assertThat(observationFormatted).doesNotContain("SECRET_OPERATION_MARKER");
    assertThat(observationFormatted).doesNotContain("FAKE_LOG_LINE");
    assertThat(observationFormatted).doesNotContain("\n");
    assertThat(observationFormatted).doesNotContain("\r");
  }

  @Test
  void individualRateLimitHeaderFlagsSurviveWithoutValues() {
    RateLimitHeaderPresence headers =
        new RateLimitHeaderPresence(true, false, true, false, true, false);

    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
            429,
            ContentFamily.JSON,
            RetryAfterParseResult.absent(),
            SetCookieCount.ZERO,
            headers,
            new RequestShapeObservation(
                RequestShapeObservation.RequestMethodShape.POST,
                RequestShapeObservation.RequestContentTypeShape.FORM_URLENCODED,
                true,
                true,
                true,
                true,
                true),
            new SessionCookieMutationObservation(
                SessionCookieCountBucket.ZERO, SessionCookieCountBucket.ZERO, false, 0, 0, 0));

    String formatted =
        VulcanRateLimitLogFormatter.format(
            observation,
            "GetPlanLekcjiContext",
            DelaySource.FALLBACK,
            EffectiveDelayBucket.LE_30_SECONDS,
            ResilienceDecision.DEFERRED_GATE,
            1,
            3);

    assertThat(formatted).contains("rateLimitLimitPresent=true");
    assertThat(formatted).contains("rateLimitRemainingPresent=false");
    assertThat(formatted).contains("rateLimitResetPresent=true");
    assertThat(formatted).contains("xRateLimitLimitPresent=false");
    assertThat(formatted).contains("xRateLimitRemainingPresent=true");
    assertThat(formatted).contains("xRateLimitResetPresent=false");
    // Verify values are not present
    assertThat(formatted).doesNotContain("100");
    assertThat(formatted).doesNotContain("60");
  }
}
