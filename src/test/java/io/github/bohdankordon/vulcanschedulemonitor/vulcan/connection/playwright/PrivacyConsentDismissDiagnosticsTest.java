package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright.PrivacyConsentDismissObservation.Fact.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The real consent wait is driven through mocked Playwright interfaces. No browser/network. */
class PrivacyConsentDismissDiagnosticsTest {
  static final String SECRETS =
      "SUPER_SECRET_PASSWORD SUPER_SECRET_COOKIE SECRET_TENANT "
          + "SECRET_FRAME_URL SECRET_APPGUID https://external.example/private private exception text "
          + "pointer-events: none 12345.6789 98765.4321 <div>private DOM</div>";
  private final Page page = mock(Page.class, RETURNS_DEEP_STUBS);
  private final ElementHandle heading = mock(ElementHandle.class);
  private final ElementHandle container = mock(ElementHandle.class);
  private final AtomicReference<PrivacyConsentDismissObservation> observed =
      new AtomicReference<>();
  private final AtomicReference<PrivacyConsentOperation> operation = new AtomicReference<>();
  private VulcanPrivacyConsentFrameTest.FrameConsent consent;
  private Locator knownHeadings;
  private Locator knownContainer;

  @BeforeEach
  void setUp() {
    when(page.url()).thenReturn(VulcanPrivacyConsentFrameTest.PAGE_URL);
    consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    knownHeadings =
        consent
            .frame()
            .getByText(VulcanPrivacyConsent.HEADING)
            .filter(new Locator.FilterOptions().setVisible(true));
    knownContainer = knownHeadings.first().locator(VulcanPrivacyConsent.DIALOG);
    when(knownHeadings.elementHandles()).thenReturn(List.of(heading));
    when(knownContainer.elementHandles()).thenReturn(List.of(container));
    when(heading.evaluate(PrivacyConsentDismissDiagnostics.HEADING_PRESENT)).thenReturn(true);
    when(container.isVisible()).thenReturn(true);
    when(consent.owner().evaluate(PrivacyConsentDismissDiagnostics.OWNER_SIGNALS))
        .thenReturn(signals(false, false, false, false));
    doNothing().when(consent.accept()).click(any(Locator.ClickOptions.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void detachmentOrHiddenOwnerRemainsSuccessful(boolean detached) {
    doAnswer(
            invocation -> {
              if (detached) when(consent.frame().isDetached()).thenReturn(true);
              else when(consent.owner().isVisible()).thenReturn(false);
              clearInvocations(consent.owner(), heading, container);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    dismiss();
    assertThat(operation.get()).isEqualTo(PrivacyConsentOperation.COMPLETED);
    assertThat(observed.get().failure()).isEqualTo(PrivacyConsentDismissFailureKind.NOT_APPLICABLE);
    assertThat(observed.get().state())
        .isEqualTo(
            detached
                ? PrivacyConsentDismissState.FRAME_DETACHED
                : PrivacyConsentDismissState.OWNER_HIDDEN);
    if (detached) {
      verify(consent.owner(), never()).isVisible();
      verify(consent.owner(), never()).evaluate(anyString());
      verify(heading, never()).evaluate(anyString());
      verify(container, never()).isVisible();
    }
    assertSingleClickAndWait();
  }

  @ParameterizedTest
  @CsvSource({
    "true,true,false,false,false,false,OWNER_VISIBLE_INTERACTIVE",
    "false,false,false,false,false,false,OWNER_VISIBLE_INTERACTIVE",
    "false,false,false,true,false,false,OWNER_VISIBLE_NON_INTERACTIVE",
    "false,false,true,false,false,false,OWNER_ZERO_AREA",
    "false,false,false,false,true,false,OWNER_VISIBLE_NON_INTERACTIVE",
    "false,false,false,false,false,true,OWNER_VISIBLE_INTERACTIVE",
    "true,true,false,true,false,false,OWNER_VISIBLE_NON_INTERACTIVE",
    "false,true,false,false,false,false,OWNER_VISIBLE_INTERACTIVE"
  })
  void visibleOwnerStillTimesOutRegardlessOfDiagnosticSignals(
      boolean headingPresent,
      boolean containerVisible,
      boolean zeroArea,
      boolean pointerNone,
      boolean inert,
      boolean ariaHidden,
      PrivacyConsentDismissState state) {
    doAnswer(
            invocation -> {
              when(heading.evaluate(PrivacyConsentDismissDiagnostics.HEADING_PRESENT))
                  .thenReturn(headingPresent);
              when(container.isVisible()).thenReturn(containerVisible);
              when(consent.owner().evaluate(PrivacyConsentDismissDiagnostics.OWNER_SIGNALS))
                  .thenReturn(signals(zeroArea, pointerNone, inert, ariaHidden));
              clearInvocations(knownHeadings, knownContainer);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    assertThatThrownBy(this::dismiss).isInstanceOf(TimeoutError.class);
    assertThat(observed.get())
        .isEqualTo(
            new PrivacyConsentDismissObservation(
                PrivacyConsentDismissFailureKind.WAIT_TIMEOUT,
                state,
                PrivacyConsentDismissObservation.Fact.of(headingPresent),
                PrivacyConsentDismissObservation.Fact.of(containerVisible),
                PrivacyConsentDismissObservation.Fact.of(ariaHidden)));
    assertThat(operation.get()).isEqualTo(PrivacyConsentOperation.DISMISS_WAIT);
    // Inner elements were pinned before click. No heading-relative locator is re-resolved later.
    verifyNoInteractions(knownHeadings, knownContainer);
    verify(heading).dispose();
    verify(container).dispose();
    assertSingleClickAndWait();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void ownerReadExceptionIsNotConfusedWithTheWaitDeadline(boolean timeout) {
    PlaywrightException fault =
        timeout ? new TimeoutError(SECRETS) : new PlaywrightException(SECRETS);
    doAnswer(
            invocation -> {
              when(consent.owner().isVisible()).thenThrow(fault);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    assertThatThrownBy(this::dismiss).isSameAs(fault);
    assertThat(observed.get().failure())
        .isEqualTo(PrivacyConsentDismissFailureKind.SURFACE_STATE_READ_FAILURE);
    assertThat(observed.get().state()).isEqualTo(PrivacyConsentDismissState.STATE_READ_FAILED);
    assertSingleClickAndWait();
  }

  @Test
  void trustBoundaryFailureDuringWaitKeepsExistingSafetyCategoryAndPreventsDiagnosticDomReads() {
    doAnswer(
            invocation -> {
              when(consent.frame().url()).thenReturn("https://external.example/SECRET_FRAME_URL");
              clearInvocations(consent.owner(), heading, container);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    assertThatThrownBy(this::dismiss)
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            fault ->
                assertThat(fault.category())
                    .isEqualTo(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW));
    assertThat(observed.get())
        .isEqualTo(
            PrivacyConsentDismissObservation.unavailable(
                PrivacyConsentDismissFailureKind.TRUST_VALIDATION_FAILURE,
                PrivacyConsentDismissState.UNAVAILABLE));
    verify(consent.owner(), never()).isVisible();
    verify(consent.owner(), never()).evaluate(anyString());
    verify(heading, never()).evaluate(anyString());
    verify(container, never()).isVisible();
    assertSingleClickAndWait();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failureFromWaitItselfRetainsItsIdentityAndFiniteKind(boolean timeout) {
    PlaywrightException fault =
        timeout ? new TimeoutError(SECRETS) : new PlaywrightException(SECRETS);
    doAnswer(
            invocation -> {
              doThrow(fault)
                  .when(page)
                  .waitForCondition(any(), argThat(options -> options.timeout == 3_000));
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    assertThatThrownBy(this::dismiss).isSameAs(fault);
    assertThat(observed.get().failure())
        .isEqualTo(
            timeout
                ? PrivacyConsentDismissFailureKind.WAIT_TIMEOUT
                : PrivacyConsentDismissFailureKind.OTHER_PLAYWRIGHT_FAILURE);
    assertThat(observed.get().state())
        .isEqualTo(PrivacyConsentDismissState.OWNER_VISIBLE_INTERACTIVE);
    assertSingleClickAndWait();
  }

  @Test
  void originalCatchStillReplacesWaitFailureWhenTrustValidationFails() {
    doAnswer(
            invocation -> {
              doAnswer(
                      wait -> {
                        when(consent.frame().url())
                            .thenReturn("https://external.example/SECRET_FRAME_URL");
                        throw new TimeoutError(SECRETS);
                      })
                  .when(page)
                  .waitForCondition(any(), argThat(options -> options.timeout == 3_000));
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    assertThatThrownBy(this::dismiss)
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            fault ->
                assertThat(fault.category())
                    .isEqualTo(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW));
    assertThat(observed.get().failure())
        .isEqualTo(PrivacyConsentDismissFailureKind.TRUST_VALIDATION_FAILURE);
    assertSingleClickAndWait();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void extraSnapshotReadFailureCannotChangeSuccessOrWaitTimeout(boolean hiddenOwner) {
    doAnswer(
            invocation -> {
              when(consent.owner().isVisible()).thenReturn(!hiddenOwner);
              when(container.isVisible()).thenThrow(new PlaywrightException(SECRETS));
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    if (hiddenOwner) assertThatCode(this::dismiss).doesNotThrowAnyException();
    else assertThatThrownBy(this::dismiss).isInstanceOf(TimeoutError.class);
    assertThat(observed.get().state()).isEqualTo(PrivacyConsentDismissState.STATE_READ_FAILED);
    assertThat(observed.get().failure())
        .isEqualTo(
            hiddenOwner
                ? PrivacyConsentDismissFailureKind.NOT_APPLICABLE
                : PrivacyConsentDismissFailureKind.WAIT_TIMEOUT);
    assertSingleClickAndWait();
  }

  @Test
  void optionalPinFailureCannotBlockClickAndUnavailableInnerFactsAreExplicit() {
    when(knownHeadings.elementHandles()).thenThrow(new PlaywrightException(SECRETS));
    assertThatThrownBy(this::dismiss).isInstanceOf(TimeoutError.class);
    assertThat(observed.get().headingPresent()).isEqualTo(UNAVAILABLE);
    assertThat(observed.get().containerVisible()).isEqualTo(UNAVAILABLE);
    assertThat(observed.get().state())
        .isEqualTo(PrivacyConsentDismissState.OWNER_VISIBLE_INTERACTIVE);
    assertSingleClickAndWait();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void throwingObserverAndDiagnosticCleanupDoNotAffectConsent(boolean hiddenOwner) {
    doAnswer(
            invocation -> {
              when(consent.owner().isVisible()).thenReturn(!hiddenOwner);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    doThrow(new IllegalStateException(SECRETS)).when(heading).dispose();
    Runnable dismiss =
        () ->
            VulcanPrivacyConsent.dismissIfPresent(
                page,
                new PortalUrlValidator(),
                operation::set,
                result -> {
                  observed.set(result);
                  throw new IllegalStateException(SECRETS);
                });
    if (hiddenOwner) assertThatCode(dismiss::run).doesNotThrowAnyException();
    else assertThatThrownBy(dismiss::run).isInstanceOf(TimeoutError.class);
    assertThat(operation.get())
        .isEqualTo(
            hiddenOwner ? PrivacyConsentOperation.COMPLETED : PrivacyConsentOperation.DISMISS_WAIT);
    assertSingleClickAndWait();
  }

  @ParameterizedTest
  @ValueSource(strings = {"string", "rawValue", "extraKey", "geometry", "null"})
  void unexpectedEvaluateResultsAreDiscardedBeforeTheFiniteFormatter(String variant) {
    Object result =
        switch (variant) {
          case "string" -> SECRETS;
          case "rawValue" ->
              Map.of(
                  "zeroArea",
                  SECRETS,
                  "pointerEventsNone",
                  false,
                  "inert",
                  false,
                  "ariaHidden",
                  false);
          case "extraKey" ->
              Map.of(
                  "zeroArea",
                  false,
                  "pointerEventsNone",
                  false,
                  "inert",
                  false,
                  "ariaHidden",
                  false,
                  "SECRET_TENANT",
                  true);
          case "geometry" ->
              Map.of(
                  "zeroArea",
                  12345.6789,
                  "pointerEventsNone",
                  false,
                  "inert",
                  false,
                  "ariaHidden",
                  false);
          default -> null;
        };
    when(consent.owner().evaluate(PrivacyConsentDismissDiagnostics.OWNER_SIGNALS))
        .thenReturn(result);
    assertThatThrownBy(this::dismiss).isInstanceOf(TimeoutError.class);
    assertThat(observed.get().state()).isEqualTo(PrivacyConsentDismissState.STATE_READ_FAILED);
    String log =
        PlaywrightVulcanBrowserAuthenticator.formatFailure(
            BrowserAuthStage.INITIAL_PORTAL_CONSENT,
            operation.get(),
            observed.get(),
            VulcanAuthFailureCategory.TRANSIENT);
    assertFiniteLog(log);
    assertSingleClickAndWait();
  }

  @Test
  void formatterHasNoArbitraryStringInputAndFiniteCombinationsHaveAClosedShape() throws Exception {
    var formatter =
        PlaywrightVulcanBrowserAuthenticator.class.getDeclaredMethod(
            "formatFailure",
            BrowserAuthStage.class,
            PrivacyConsentOperation.class,
            PrivacyConsentDismissObservation.class,
            VulcanAuthFailureCategory.class);
    assertThatThrownBy(
            () ->
                formatter.invoke(
                    null,
                    SECRETS,
                    PrivacyConsentOperation.DISMISS_WAIT,
                    PrivacyConsentDismissObservation.NONE,
                    VulcanAuthFailureCategory.TRANSIENT))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(PrivacyConsentDismissObservation.class.getRecordComponents())
        .allSatisfy(component -> assertThat(component.getType().isEnum()).isTrue());
    for (var failure : PrivacyConsentDismissFailureKind.values()) {
      for (var state : PrivacyConsentDismissState.values()) {
        for (var fact : PrivacyConsentDismissObservation.Fact.values()) {
          assertFiniteLog(
              PlaywrightVulcanBrowserAuthenticator.formatFailure(
                  BrowserAuthStage.INITIAL_PORTAL_CONSENT,
                  PrivacyConsentOperation.DISMISS_WAIT,
                  new PrivacyConsentDismissObservation(failure, state, fact, fact, fact),
                  VulcanAuthFailureCategory.TRANSIENT));
        }
      }
    }
  }

  static void assertFiniteLog(String log) {
    assertThat(log)
        .matches(
            "VULCAN browser authentication failed: stage=[A-Z_]+ consentOperation=[A-Z_]+"
                + " dismissFailure=[A-Z_]+ dismissState=[A-Z_]+ headingPresent=(TRUE|FALSE|UNAVAILABLE)"
                + " containerVisible=(TRUE|FALSE|UNAVAILABLE) anyOwnerAriaHidden=(TRUE|FALSE|UNAVAILABLE) category=[A-Z_]+");
    assertThat(log)
        .doesNotContain(
            "SUPER_SECRET_PASSWORD",
            "SUPER_SECRET_COOKIE",
            "SECRET_TENANT",
            "SECRET_FRAME_URL",
            "SECRET_APPGUID",
            "https://",
            "private exception text",
            "pointer-events",
            "12345.6789",
            "98765.4321",
            "<div>");
  }

  static Map<String, Boolean> signals(
      boolean zeroArea, boolean pointerNone, boolean inert, boolean ariaHidden) {
    return Map.of(
        "zeroArea",
        zeroArea,
        "pointerEventsNone",
        pointerNone,
        "inert",
        inert,
        "ariaHidden",
        ariaHidden);
  }

  private void dismiss() {
    VulcanPrivacyConsent.dismissIfPresent(
        page, new PortalUrlValidator(), operation::set, observed::set);
  }

  private void assertSingleClickAndWait() {
    verify(consent.accept()).click(argThat(options -> options.timeout == 3_000));
    verify(page).waitForCondition(any(), argThat(options -> options.timeout == 2_000));
    verify(page).waitForCondition(any(), argThat(options -> options.timeout == 3_000));
    verify(page, never()).waitForTimeout(anyDouble());
  }
}
