package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

public enum MonitoringOutcomeCategory {
  SUCCESS,
  BASELINE_ESTABLISHED,
  TRANSITIONS,
  DEFERRED_RATE_LIMIT,
  AUTHENTICATION_REQUIRED,
  TRANSIENT_FAILURE_EXHAUSTED,
  PERMANENT_FAILURE,
  PROTOCOL_FAILURE
}
