package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

/** Location/type of a dismissal fault, never exception details. */
enum PrivacyConsentDismissFailureKind {
  NOT_APPLICABLE,
  WAIT_TIMEOUT,
  TRUST_VALIDATION_FAILURE,
  SURFACE_STATE_READ_FAILURE,
  OTHER_PLAYWRIGHT_FAILURE,
  OTHER_FAILURE
}
