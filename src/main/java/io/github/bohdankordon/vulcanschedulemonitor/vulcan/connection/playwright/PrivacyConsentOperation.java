package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

/** Finite operation boundaries only; never carry page, frame or exception data. */
enum PrivacyConsentOperation {
  NOT_STARTED,
  DISCOVERY,
  TRUST_VALIDATION,
  ACTION_RESOLUTION,
  ACCEPT_CLICK,
  DISMISS_WAIT,
  FINAL_VALIDATION,
  COMPLETED
}
