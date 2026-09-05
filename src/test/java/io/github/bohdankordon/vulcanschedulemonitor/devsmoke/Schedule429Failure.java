package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

/** Finite harness diagnostics, with no original exception, message, or provider data retained. */
public final class Schedule429Failure extends RuntimeException {
  public enum Stage {
    INVESTIGATION_INPUT,
    JAVA_TRANSPORT_CALIBRATION,
    AUTHENTICATED_BROWSER_READY,
    CATALOG_READY,
    TARGET_SELECTION,
    PLAN_CONTEXT_DISCOVERY,
    PLAN_CONTEXT_NAVIGATION,
    BROWSER_REQUEST_OBSERVER_SETUP,
    BROWSER_CONTROL_TRIGGER,
    BROWSER_CONTROL_WAIT,
    POST_PLAN_SESSION_CAPTURE,
    JAVA_COMPARISON_SETUP,
    JAVA_COMPARISON,
    BROWSER_CLEANUP
  }

  public enum Category {
    NOT_FOUND,
    AMBIGUOUS,
    NOT_ACTIONABLE,
    NAVIGATION_TIMEOUT,
    REQUEST_NOT_OBSERVED,
    UNEXPECTED_PAGE_STATE,
    INTERNAL_INVARIANT,
    PLAYWRIGHT_TRANSIENT
  }

  private final Category category;

  public Schedule429Failure(Category category) {
    super(category.name(), null, false, false);
    this.category = category;
  }

  public Category category() {
    return category;
  }
}
