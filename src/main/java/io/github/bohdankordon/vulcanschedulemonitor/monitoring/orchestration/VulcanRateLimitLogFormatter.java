package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitResponseObservation;

/**
 * Produces a strictly sanitized, single-line log message for rate-limited schedule requests.
 * Accepts only finite enums, bounded counts, and booleans.
 */
final class VulcanRateLimitLogFormatter {

  private VulcanRateLimitLogFormatter() {}

  static String format(
      RateLimitResponseObservation observation,
      String fallbackOperation,
      DelaySource delaySource,
      EffectiveDelayBucket delayBucket,
      ResilienceDecision decision,
      int attempt,
      int maxAttempts) {
    if (observation == null) {
      return "VULCAN schedule rate limited: operation="
          + (fallbackOperation != null ? fallbackOperation : "unknown")
          + " status=429"
          + " delaySource="
          + delaySource
          + " delayBucket="
          + delayBucket
          + " decision="
          + decision
          + " attempt="
          + attempt
          + "/"
          + maxAttempts;
    }

    return "VULCAN schedule rate limited: operation="
        + observation.operation()
        + " status="
        + observation.statusCode()
        + " content="
        + observation.contentFamily()
        + " retryAfter="
        + observation.retryAfterRepresentation()
        + " delaySource="
        + delaySource
        + " delayBucket="
        + delayBucket
        + " decision="
        + decision
        + " setCookie="
        + observation.setCookieCount()
        + " sessionCookiesBefore="
        + observation.sessionMutation().sessionCookiesBefore()
        + " sessionCookiesAfter="
        + observation.sessionMutation().sessionCookiesAfter()
        + " cookieMaterialChanged="
        + observation.sessionMutation().cookieMaterialChanged()
        + " cookieAdded="
        + observation.sessionMutation().cookieIdentityAddedCount()
        + " cookieRemoved="
        + observation.sessionMutation().cookieIdentityRemovedCount()
        + " cookieValueChanged="
        + observation.sessionMutation().cookieValueChangedCount()
        + " method="
        + observation.requestShape().method()
        + " contentType="
        + observation.requestShape().contentType()
        + " originPresent="
        + observation.requestShape().originPresent()
        + " refererPresent="
        + observation.requestShape().refererPresent()
        + " tokenPresent="
        + observation.requestShape().verificationTokenPresent()
        + " appGuidPresent="
        + observation.requestShape().appGuidPresent()
        + " xRequestedWithPresent="
        + observation.requestShape().xRequestedWithPresent()
        + " rateLimitHeadersPresent="
        + observation.rateLimitHeaders().anyPresent()
        + " attempt="
        + attempt
        + "/"
        + maxAttempts;
  }
}
