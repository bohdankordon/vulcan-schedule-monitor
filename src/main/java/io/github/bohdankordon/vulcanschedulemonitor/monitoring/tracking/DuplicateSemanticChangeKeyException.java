package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

public final class DuplicateSemanticChangeKeyException extends IllegalArgumentException {

  public DuplicateSemanticChangeKeyException(String changeKey) {
    super("Successful snapshot contains duplicate semantic change key: " + changeKey);
  }
}
