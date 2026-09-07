package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

/** Finite diagnostic values; never include page or account data. */
enum BrowserAuthStage {
  INITIAL_NAVIGATION,
  INITIAL_PORTAL_CONSENT,
  DIRECT_LOGIN_DISCOVERY,
  DIRECT_LOGIN_NAVIGATION,
  POST_DIRECT_LOGIN_CONSENT,
  LOGIN_FORM_VALIDATION,
  CREDENTIAL_SUBMISSION,
  POST_LOGIN_VALIDATION,
  SESSION_CAPTURE
}
