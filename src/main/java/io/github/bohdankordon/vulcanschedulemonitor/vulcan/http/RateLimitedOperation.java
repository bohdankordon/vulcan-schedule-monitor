package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

/** Finite allowlisted operations eligible for sanitized rate-limit diagnostics. */
public enum RateLimitedOperation {
  GET_PLAN_LEKCJI_CONTEXT("GetPlanLekcjiContext"),
  OTHER("OTHER");

  private final String label;

  RateLimitedOperation(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static RateLimitedOperation from(String operation) {
    if ("GetPlanLekcjiContext".equals(operation)) {
      return GET_PLAN_LEKCJI_CONTEXT;
    }
    return OTHER;
  }

  @Override
  public String toString() {
    return label;
  }
}
