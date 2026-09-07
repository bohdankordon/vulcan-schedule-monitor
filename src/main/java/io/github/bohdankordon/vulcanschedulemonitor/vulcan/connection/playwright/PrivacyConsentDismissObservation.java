package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import java.util.Objects;

/** Closed log boundary: no strings, browser objects, numeric geometry or exception objects. */
record PrivacyConsentDismissObservation(
    PrivacyConsentDismissFailureKind failure,
    PrivacyConsentDismissState state,
    Fact headingPresent,
    Fact containerVisible,
    Fact anyOwnerAriaHidden) {
  enum Fact {
    TRUE,
    FALSE,
    UNAVAILABLE;

    static Fact of(boolean value) {
      return value ? TRUE : FALSE;
    }
  }

  static final PrivacyConsentDismissObservation NONE =
      unavailable(
          PrivacyConsentDismissFailureKind.NOT_APPLICABLE, PrivacyConsentDismissState.UNAVAILABLE);

  PrivacyConsentDismissObservation {
    Objects.requireNonNull(failure);
    Objects.requireNonNull(state);
    Objects.requireNonNull(headingPresent);
    Objects.requireNonNull(containerVisible);
    Objects.requireNonNull(anyOwnerAriaHidden);
  }

  static PrivacyConsentDismissObservation unavailable(
      PrivacyConsentDismissFailureKind failure, PrivacyConsentDismissState state) {
    return new PrivacyConsentDismissObservation(
        failure, state, Fact.UNAVAILABLE, Fact.UNAVAILABLE, Fact.UNAVAILABLE);
  }
}
