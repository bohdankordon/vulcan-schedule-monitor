package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright.SessionCaptureObservation.*;

/** Per-attempt accumulator. No method accepts request/header/cookie values or exceptions. */
final class SessionCaptureDiagnostics {
  private int allowedRequests;
  private int completeRequests;
  private int candidates;
  private int candidatesWithCookies;
  private boolean sawReferer;
  private boolean sawVerificationToken;
  private boolean sawAppGuid;
  private boolean exhausted;
  private boolean materialCaptured;
  private CookieCount cookieCount = CookieCount.UNAVAILABLE;
  private SessionCaptureFailureKind rejection = SessionCaptureFailureKind.NOT_APPLICABLE;

  synchronized void allowedRequest() {
    allowedRequests = Math.min(6, allowedRequests + 1);
  }

  synchronized void referer(boolean present) {
    sawReferer |= present;
  }

  synchronized void verificationToken(boolean present) {
    sawVerificationToken |= present;
  }

  synchronized void appGuid(boolean present) {
    sawAppGuid |= present;
  }

  synchronized void completeRequest() {
    completeRequests = Math.min(2, completeRequests + 1);
  }

  synchronized void candidate() {
    candidates = Math.min(2, candidates + 1);
  }

  synchronized void candidateWithCookies() {
    candidatesWithCookies = Math.min(2, candidatesWithCookies + 1);
  }

  synchronized void cookies(int count) {
    cookieCount = CookieCount.of(count);
  }

  synchronized void exhausted() {
    exhausted = true;
  }

  synchronized void materialCaptured() {
    materialCaptured = true;
  }

  synchronized void rejected(SessionCaptureFailureKind reason) {
    if (precedence(reason) > precedence(rejection)) rejection = reason;
  }

  // Deterministic across candidate order. Unexpected aborts are handled separately in snapshot.
  private static int precedence(SessionCaptureFailureKind reason) {
    return switch (reason) {
      case MATERIAL_REJECTED -> 6;
      case REFERER_REJECTED -> 5;
      case APPLICATION_BASE_REJECTED -> 4;
      case NO_MATCHING_COOKIES -> 3;
      case OTHER_PROTOCOL_FAILURE -> 2;
      default -> 0;
    };
  }

  synchronized SessionCaptureObservation snapshot() {
    SessionCaptureFailureKind failure;
    if (materialCaptured) failure = SessionCaptureFailureKind.NOT_APPLICABLE;
    else if (exhausted && rejection != SessionCaptureFailureKind.NOT_APPLICABLE)
      failure = rejection;
    else if (allowedRequests == 0) failure = SessionCaptureFailureKind.NO_ALLOWED_REQUEST;
    else if (completeRequests == 0) failure = SessionCaptureFailureKind.NO_COMPLETE_REQUEST;
    else failure = SessionCaptureFailureKind.OTHER_PROTOCOL_FAILURE;
    return new SessionCaptureObservation(
        failure,
        AllowedRequestCount.of(allowedRequests),
        CompleteRequestCount.of(completeRequests),
        sawReferer,
        sawVerificationToken,
        sawAppGuid,
        completeRequests > 0,
        CompleteRequestCount.of(candidates),
        CompleteRequestCount.of(candidatesWithCookies),
        cookieCount);
  }
}
