package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

/** Sanitized HTTP/session failure categories exposed at the VULCAN boundary. */
public enum VulcanFailureCategory {
  AUTHENTICATION_REQUIRED,
  RATE_LIMITED,
  SERVER_ERROR,
  PERMANENT_HTTP,
  TRANSPORT_ERROR,
  SESSION_REDIRECT,
  UNEXPECTED_HTML;

  static VulcanFailureCategory forStatus(int statusCode) {
    if (statusCode == 401 || statusCode == 403) {
      return AUTHENTICATION_REQUIRED;
    }
    if (statusCode == 429) {
      return RATE_LIMITED;
    }
    if (statusCode >= 300 && statusCode <= 399) {
      return SESSION_REDIRECT;
    }
    if (statusCode >= 500 && statusCode <= 599) {
      return SERVER_ERROR;
    }
    return PERMANENT_HTTP;
  }
}
