package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

public final class VulcanAuthenticationException extends RuntimeException {

  private final VulcanAuthFailureCategory category;

  public VulcanAuthenticationException(VulcanAuthFailureCategory category) {
    super("VULCAN authentication failed: " + category);
    this.category = category;
  }

  public VulcanAuthFailureCategory category() {
    return category;
  }
}
