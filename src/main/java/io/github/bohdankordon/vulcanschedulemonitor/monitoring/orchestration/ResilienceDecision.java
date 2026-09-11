package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

public enum ResilienceDecision {
  INLINE_RETRY,
  DEFERRED_GATE,
  AUTHENTICATION_REQUIRED
}
