package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.ContentFamily;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionCookieMutationObservation;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Finite observation of an HTTP 429 rate-limit response and surrounding request/session state.
 * Deliberately excludes raw headers, URLs, cookies, request bodies, and tokens.
 */
public record RateLimitResponseObservation(
    String operation,
    int statusCode,
    ContentFamily contentFamily,
    RetryAfterParseResult retryAfterResult,
    SetCookieCount setCookieCount,
    RateLimitHeaderPresence rateLimitHeaders,
    RequestShapeObservation requestShape,
    SessionCookieMutationObservation sessionMutation) {

  public RateLimitResponseObservation {
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(contentFamily, "contentFamily must not be null");
    Objects.requireNonNull(retryAfterResult, "retryAfterResult must not be null");
    Objects.requireNonNull(setCookieCount, "setCookieCount must not be null");
    Objects.requireNonNull(rateLimitHeaders, "rateLimitHeaders must not be null");
    Objects.requireNonNull(requestShape, "requestShape must not be null");
    Objects.requireNonNull(sessionMutation, "sessionMutation must not be null");
  }

  public RetryAfterRepresentation retryAfterRepresentation() {
    return retryAfterResult.representation();
  }

  public Optional<Duration> retryAfterDuration() {
    return retryAfterResult.delay();
  }

  @Override
  public String toString() {
    return "RateLimitResponseObservation[operation="
        + operation
        + ", status="
        + statusCode
        + ", content="
        + contentFamily
        + ", retryAfter="
        + retryAfterResult.representation()
        + ", setCookie="
        + setCookieCount
        + ", sessionBefore="
        + sessionMutation.sessionCookiesBefore()
        + ", sessionAfter="
        + sessionMutation.sessionCookiesAfter()
        + ", cookieMaterialChanged="
        + sessionMutation.cookieMaterialChanged()
        + "]";
  }
}
