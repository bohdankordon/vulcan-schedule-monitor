package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

/** A sanitized failure while calling a VULCAN endpoint. */
public final class VulcanHttpException extends RuntimeException {

  private final String operation;
  private final Integer statusCode;

  private VulcanHttpException(String message, String operation, Integer statusCode) {
    super(message);
    this.operation = operation;
    this.statusCode = statusCode;
  }

  public static VulcanHttpException responseFailure(String operation, int statusCode) {
    return new VulcanHttpException(
        "VULCAN " + operation + " request failed with HTTP " + statusCode, operation, statusCode);
  }

  public static VulcanHttpException transportFailure(String operation) {
    return new VulcanHttpException(
        "VULCAN " + operation + " request could not be completed", operation, null);
  }

  public String operation() {
    return operation;
  }

  public Integer statusCode() {
    return statusCode;
  }
}
