package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

public enum SourceFailureKind {
  AUTHENTICATION_REQUIRED,
  TRANSIENT_RECOVERY_FAILURE,
  DEFERRED_RATE_LIMIT,
  TRANSIENT_FAILURE_EXHAUSTED,
  PERMANENT_FAILURE,
  PROTOCOL_FAILURE,
  INTERRUPTED
}
