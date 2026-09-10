package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import org.springframework.http.HttpHeaders;

/** Finite presence flags for standard and common vendor rate-limit response headers. */
public record RateLimitHeaderPresence(
    boolean rateLimitLimit,
    boolean rateLimitRemaining,
    boolean rateLimitReset,
    boolean xRateLimitLimit,
    boolean xRateLimitRemaining,
    boolean xRateLimitReset) {

  public static RateLimitHeaderPresence fromHeaders(HttpHeaders headers) {
    if (headers == null) {
      return new RateLimitHeaderPresence(false, false, false, false, false, false);
    }
    return new RateLimitHeaderPresence(
        headers.containsHeader("RateLimit-Limit"),
        headers.containsHeader("RateLimit-Remaining"),
        headers.containsHeader("RateLimit-Reset"),
        headers.containsHeader("X-RateLimit-Limit"),
        headers.containsHeader("X-RateLimit-Remaining"),
        headers.containsHeader("X-RateLimit-Reset"));
  }

  public boolean anyPresent() {
    return rateLimitLimit
        || rateLimitRemaining
        || rateLimitReset
        || xRateLimitLimit
        || xRateLimitRemaining
        || xRateLimitReset;
  }
}
