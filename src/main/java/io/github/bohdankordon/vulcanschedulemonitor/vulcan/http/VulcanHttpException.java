package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import java.time.Duration;
import java.util.Optional;

/** A sanitized failure while calling a VULCAN endpoint. */
public final class VulcanHttpException extends RuntimeException {

  private final String operation;
  private final Integer statusCode;
  private final VulcanFailureCategory category;
  private final Duration retryAfter;

  private VulcanHttpException(
      String message,
      String operation,
      Integer statusCode,
      VulcanFailureCategory category,
      Duration retryAfter) {
    super(message);
    this.operation = operation;
    this.statusCode = statusCode;
    this.category = category;
    this.retryAfter = retryAfter;
  }

  public static VulcanHttpException responseFailure(String operation, int statusCode) {
    return responseFailure(operation, statusCode, null);
  }

  public static VulcanHttpException responseFailure(
      String operation, int statusCode, Duration retryAfter) {
    VulcanFailureCategory category = VulcanFailureCategory.forStatus(statusCode);
    return new VulcanHttpException(
        "VULCAN " + operation + " request failed with HTTP " + statusCode,
        operation,
        statusCode,
        category,
        retryAfter);
  }

  public static VulcanHttpException transportFailure(String operation) {
    return new VulcanHttpException(
        "VULCAN " + operation + " request could not be completed",
        operation,
        null,
        VulcanFailureCategory.TRANSPORT_ERROR,
        null);
  }

  public static VulcanHttpException unexpectedHtml(String operation) {
    return new VulcanHttpException(
        "VULCAN " + operation + " returned an unexpected HTML session response",
        operation,
        null,
        VulcanFailureCategory.UNEXPECTED_HTML,
        null);
  }

  public String operation() {
    return operation;
  }

  public Integer statusCode() {
    return statusCode;
  }

  public VulcanFailureCategory category() {
    return category;
  }

  public Optional<Duration> retryAfter() {
    return Optional.ofNullable(retryAfter);
  }
}
