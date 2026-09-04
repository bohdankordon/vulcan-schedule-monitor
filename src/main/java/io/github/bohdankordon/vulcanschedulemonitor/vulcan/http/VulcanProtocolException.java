package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

/** A sanitized failure to understand a VULCAN protocol response. */
public final class VulcanProtocolException extends RuntimeException {

  private final String operation;

  public VulcanProtocolException(String operation) {
    super("VULCAN " + operation + " returned an invalid protocol response");
    this.operation = operation;
  }

  public String operation() {
    return operation;
  }
}
