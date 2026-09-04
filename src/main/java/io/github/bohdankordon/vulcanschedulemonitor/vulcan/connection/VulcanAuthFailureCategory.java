package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

public enum VulcanAuthFailureCategory {
  INVALID_CREDENTIALS,
  MFA_REQUIRED,
  CAPTCHA_REQUIRED,
  UNSUPPORTED_AUTH_FLOW,
  TRANSIENT,
  PROTOCOL_FAILURE
}
