package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

/** Closed diagnostic boundary: only buckets, presence booleans and one finite failure kind. */
record SessionCaptureObservation(
    SessionCaptureFailureKind failure,
    AllowedRequestCount allowedRequests,
    CompleteRequestCount completeRequests,
    boolean sawReferer,
    boolean sawVerificationToken,
    boolean sawAppGuid,
    boolean sawAllRequiredHeadersTogether,
    CompleteRequestCount candidates,
    CompleteRequestCount candidatesWithCookies,
    CookieCount cookieCount) {
  enum AllowedRequestCount {
    ZERO,
    ONE,
    TWO_TO_FIVE,
    SIX_PLUS;

    static AllowedRequestCount of(int count) {
      return count == 0 ? ZERO : count == 1 ? ONE : count <= 5 ? TWO_TO_FIVE : SIX_PLUS;
    }
  }

  enum CompleteRequestCount {
    ZERO,
    ONE,
    TWO_PLUS;

    static CompleteRequestCount of(int count) {
      return count == 0 ? ZERO : count == 1 ? ONE : TWO_PLUS;
    }
  }

  enum CookieCount {
    UNAVAILABLE,
    ZERO,
    ONE,
    TWO_TO_FOUR,
    FIVE_PLUS;

    static CookieCount of(int count) {
      return count == 0 ? ZERO : count == 1 ? ONE : count <= 4 ? TWO_TO_FOUR : FIVE_PLUS;
    }
  }
}
