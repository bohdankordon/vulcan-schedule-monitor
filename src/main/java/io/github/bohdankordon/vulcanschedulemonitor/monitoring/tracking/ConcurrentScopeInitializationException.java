package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

public final class ConcurrentScopeInitializationException extends RuntimeException {

  public ConcurrentScopeInitializationException(Throwable cause) {
    super("Tracking scope was initialized concurrently; retry the successful snapshot", cause);
  }
}
