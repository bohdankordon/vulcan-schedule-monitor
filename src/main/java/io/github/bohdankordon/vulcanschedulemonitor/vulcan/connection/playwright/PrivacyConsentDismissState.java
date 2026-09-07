package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

/** Observational only: none of these values can make consent count as dismissed. */
enum PrivacyConsentDismissState {
  UNAVAILABLE,
  FRAME_DETACHED,
  OWNER_HIDDEN,
  OWNER_VISIBLE_INTERACTIVE,
  OWNER_VISIBLE_NON_INTERACTIVE,
  OWNER_ZERO_AREA,
  NO_IFRAME,
  STATE_READ_FAILED
}
