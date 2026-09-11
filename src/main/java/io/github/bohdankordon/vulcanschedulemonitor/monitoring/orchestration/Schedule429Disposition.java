package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

/** Finite disposition of a VULCAN schedule HTTP 429 response. */
public enum Schedule429Disposition {
  RATE_LIMITED,
  STALE_SESSION
}
