package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright.PrivacyConsentDismissObservation.Fact;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Best-effort local inspection. Wait/validation wrappers rethrow the identical exception. */
final class PrivacyConsentDismissDiagnostics {
  static final String HEADING_PRESENT = "element => element.isConnected";
  // Only approved structural booleans cross the browser boundary. Geometry/style strings stay
  // inside this fixed function. aria-hidden is informational, not an input-blocking criterion.
  static final String OWNER_SIGNALS =
      """
      element => {
        const rect = element.getBoundingClientRect();
        return {
          zeroArea: rect.width <= 0 || rect.height <= 0,
          pointerEventsNone: getComputedStyle(element).pointerEvents === 'none',
          inert: element.inert === true,
          ariaHidden: element.getAttribute('aria-hidden') === 'true'
        };
      }
      """;
  private static final Set<String> OWNER_KEYS =
      Set.of("zeroArea", "pointerEventsNone", "inert", "ariaHidden");

  private final Runnable trusted;
  private final BooleanSupplier detached;
  private final List<ElementHandle> owners;
  private final Consumer<PrivacyConsentDismissObservation> observer;
  private final List<ElementHandle> pinned = new ArrayList<>();
  private ElementHandle heading;
  private ElementHandle container;
  private boolean started;
  private PrivacyConsentDismissFailureKind failure =
      PrivacyConsentDismissFailureKind.NOT_APPLICABLE;

  PrivacyConsentDismissDiagnostics(
      Runnable trusted,
      BooleanSupplier detached,
      List<ElementHandle> owners,
      Locator knownHeadings,
      Locator knownContainer,
      ElementHandle originalContainer,
      Consumer<PrivacyConsentDismissObservation> observer) {
    this.trusted = trusted;
    this.detached = detached;
    this.owners = owners;
    this.observer = observer;
    try {
      trusted.run();
      if (detached.getAsBoolean()) return;
      // elementHandles() takes a current snapshot without adding an auto-wait. Never resolve
      // the heading-relative container again after click: the heading may have disappeared.
      heading = pin(knownHeadings);
      trusted.run();
      container = originalContainer != null ? originalContainer : pin(knownContainer);
    } catch (RuntimeException ignored) {
      // Missing diagnostic handles cannot prevent the existing trusted click/wait.
    }
  }

  private ElementHandle pin(Locator known) {
    List<ElementHandle> handles = known.elementHandles();
    pinned.addAll(handles);
    return handles.size() == 1 ? handles.getFirst() : null;
  }

  void waitFor(Runnable wait) {
    started = true;
    try {
      wait.run();
    } catch (RuntimeException exception) {
      if (failure == PrivacyConsentDismissFailureKind.NOT_APPLICABLE) {
        failure =
            exception instanceof TimeoutError
                ? PrivacyConsentDismissFailureKind.WAIT_TIMEOUT
                : exception instanceof PlaywrightException
                    ? PrivacyConsentDismissFailureKind.OTHER_PLAYWRIGHT_FAILURE
                    : exception instanceof VulcanAuthenticationException
                        ? PrivacyConsentDismissFailureKind.TRUST_VALIDATION_FAILURE
                        : PrivacyConsentDismissFailureKind.OTHER_FAILURE;
      }
      throw exception;
    }
  }

  boolean inspectBlocking(BooleanSupplier existingCheck) {
    try {
      return existingCheck.getAsBoolean();
    } catch (RuntimeException exception) {
      // This includes owner visibility and frame/trust metadata reads; claiming every generic
      // Playwright failure here came from owner.isVisible() would be misleading.
      failure =
          exception instanceof VulcanAuthenticationException
              ? PrivacyConsentDismissFailureKind.TRUST_VALIDATION_FAILURE
              : exception instanceof PlaywrightException
                  ? PrivacyConsentDismissFailureKind.SURFACE_STATE_READ_FAILURE
                  : PrivacyConsentDismissFailureKind.OTHER_FAILURE;
      throw exception;
    }
  }

  void validateAfterFailure(Runnable existingValidation) {
    try {
      existingValidation.run();
    } catch (RuntimeException exception) {
      if (started) {
        failure =
            exception instanceof VulcanAuthenticationException
                ? PrivacyConsentDismissFailureKind.TRUST_VALIDATION_FAILURE
                : PrivacyConsentDismissFailureKind.SURFACE_STATE_READ_FAILURE;
      }
      throw exception;
    }
  }

  void finish() {
    try {
      if (started) observer.accept(snapshot());
    } catch (RuntimeException ignored) {
      // Neither the extra reads nor the observer may replace the existing consent outcome.
    } finally {
      for (ElementHandle handle : pinned) {
        try {
          handle.dispose();
        } catch (RuntimeException ignored) {
          // Diagnostic handle cleanup cannot affect authentication either.
        }
      }
    }
  }

  private PrivacyConsentDismissObservation snapshot() {
    try {
      trusted.run();
      if (detached.getAsBoolean())
        return PrivacyConsentDismissObservation.unavailable(
            failure, PrivacyConsentDismissState.FRAME_DETACHED);
      Fact headingPresent = heading == null ? Fact.UNAVAILABLE : readHeading();
      trusted.run();
      Fact containerVisible = container == null ? Fact.UNAVAILABLE : Fact.of(container.isVisible());
      boolean anyHidden = false;
      boolean zeroArea = false;
      boolean nonInteractive = false;
      boolean ariaHidden = false;
      for (ElementHandle owner : owners) {
        trusted.run();
        if (detached.getAsBoolean())
          return PrivacyConsentDismissObservation.unavailable(
              failure, PrivacyConsentDismissState.FRAME_DETACHED);
        anyHidden |= !owner.isVisible();
        trusted.run();
        Object result = owner.evaluate(OWNER_SIGNALS);
        if (!(result instanceof Map<?, ?> signals)
            || !signals.keySet().equals(OWNER_KEYS)
            || signals.values().stream().anyMatch(value -> !(value instanceof Boolean))) {
          return PrivacyConsentDismissObservation.unavailable(
              failure, PrivacyConsentDismissState.STATE_READ_FAILED);
        }
        zeroArea |= Boolean.TRUE.equals(signals.get("zeroArea"));
        nonInteractive |=
            Boolean.TRUE.equals(signals.get("pointerEventsNone"))
                || Boolean.TRUE.equals(signals.get("inert"));
        ariaHidden |= Boolean.TRUE.equals(signals.get("ariaHidden"));
      }
      trusted.run();
      if (detached.getAsBoolean())
        return PrivacyConsentDismissObservation.unavailable(
            failure, PrivacyConsentDismissState.FRAME_DETACHED);
      PrivacyConsentDismissState state =
          owners.isEmpty()
              ? PrivacyConsentDismissState.NO_IFRAME
              : anyHidden
                  ? PrivacyConsentDismissState.OWNER_HIDDEN
                  : zeroArea
                      ? PrivacyConsentDismissState.OWNER_ZERO_AREA
                      : nonInteractive
                          ? PrivacyConsentDismissState.OWNER_VISIBLE_NON_INTERACTIVE
                          : PrivacyConsentDismissState.OWNER_VISIBLE_INTERACTIVE;
      return new PrivacyConsentDismissObservation(
          failure,
          state,
          headingPresent,
          containerVisible,
          owners.isEmpty() ? Fact.UNAVAILABLE : Fact.of(ariaHidden));
    } catch (VulcanAuthenticationException ignored) {
      return PrivacyConsentDismissObservation.unavailable(
          failure, PrivacyConsentDismissState.UNAVAILABLE);
    } catch (RuntimeException ignored) {
      return PrivacyConsentDismissObservation.unavailable(
          failure, PrivacyConsentDismissState.STATE_READ_FAILED);
    }
  }

  private Fact readHeading() {
    Object result = heading.evaluate(HEADING_PRESENT);
    return result instanceof Boolean value ? Fact.of(value) : Fact.UNAVAILABLE;
  }
}
