package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ElementState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Browser-free diagnostics exercise the existing consent implementation, not a copied flow. */
class PrivacyConsentDiagnosticsTest {
  private final Page page = mock(Page.class, RETURNS_DEEP_STUBS);
  private final PortalUrlValidator urls = new PortalUrlValidator();
  private final List<PrivacyConsentOperation> operations = new ArrayList<>();

  @Test
  void noConsentCompletesAfterTheExistingDiscoveryDeadlineAndFinalScan() {
    when(page.url()).thenReturn(VulcanPrivacyConsentFrameTest.PAGE_URL);
    driveCondition();
    VulcanPrivacyConsent.dismissIfPresent(page, urls, operations::add);
    assertThat(operations).endsWith(PrivacyConsentOperation.COMPLETED);
    assertThat(operations)
        .doesNotContain(PrivacyConsentOperation.ACCEPT_CLICK, PrivacyConsentOperation.DISMISS_WAIT);
    verify(page, times(2)).frames();
    verify(page).waitForCondition(any(), argThat(options -> options.timeout == 2_000));
    verify(page, never()).waitForTimeout(anyDouble());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void actualConsentCompletesAfterClickDismissalAndValidation(boolean iframe) {
    when(page.url()).thenReturn(VulcanPrivacyConsentFrameTest.PAGE_URL);
    Locator accept;
    if (iframe) {
      accept = VulcanPrivacyConsentFrameTest.knownFrameConsent(page).accept();
    } else {
      accept = VulcanPrivacyConsentTest.knownConsent(page).accept();
      driveCondition();
    }
    VulcanPrivacyConsent.dismissIfPresent(page, urls, operations::add);
    assertThat(operations)
        .containsSubsequence(
            PrivacyConsentOperation.DISCOVERY,
            PrivacyConsentOperation.TRUST_VALIDATION,
            PrivacyConsentOperation.ACTION_RESOLUTION,
            PrivacyConsentOperation.ACCEPT_CLICK,
            PrivacyConsentOperation.DISMISS_WAIT,
            PrivacyConsentOperation.FINAL_VALIDATION,
            PrivacyConsentOperation.COMPLETED);
    assertThat(operations).endsWith(PrivacyConsentOperation.COMPLETED);
    verify(accept).click(argThat(options -> options.timeout == 3_000));
    verify(page, never()).waitForTimeout(anyDouble());
  }

  @ParameterizedTest
  @EnumSource(
      value = PrivacyConsentOperation.class,
      names = {
        "DISCOVERY",
        "TRUST_VALIDATION",
        "ACTION_RESOLUTION",
        "ACCEPT_CLICK",
        "DISMISS_WAIT",
        "FINAL_VALIDATION"
      })
  void originalFailureAndLastOperationSurviveCallbackCaptureAndCleanup(
      PrivacyConsentOperation operation) {
    var consent = VulcanPrivacyConsentTest.knownConsent(page);
    var failure = new TimeoutError("private browser exception");
    Locator headings =
        page.getByText(VulcanPrivacyConsent.HEADING)
            .filter(new Locator.FilterOptions().setVisible(true));
    switch (operation) {
      case TRUST_VALIDATION -> when(page.url()).thenThrow(failure);
      case DISCOVERY -> when(page.frames()).thenThrow(failure);
      case ACTION_RESOLUTION -> when(consent.candidates().count()).thenThrow(failure);
      case ACCEPT_CLICK ->
          doThrow(failure).when(consent.accept()).click(any(Locator.ClickOptions.class));
      case DISMISS_WAIT ->
          doThrow(failure)
              .when(consent.originalContainer())
              .waitForElementState(
                  eq(ElementState.HIDDEN), any(ElementHandle.WaitForElementStateOptions.class));
      case FINAL_VALIDATION ->
          doAnswer(
                  invocation -> {
                    when(headings.count()).thenThrow(failure);
                    return null;
                  })
              .when(consent.originalContainer())
              .waitForElementState(
                  eq(ElementState.HIDDEN), any(ElementHandle.WaitForElementStateOptions.class));
      default -> throw new AssertionError(operation);
    }
    driveCondition();
    assertThatThrownBy(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls, operations::add))
        .isSameAs(failure);
    assertThat(operations).endsWith(operation).doesNotContain(PrivacyConsentOperation.COMPLETED);
    verify(
            consent.accept(),
            times(
                switch (operation) {
                  case ACCEPT_CLICK, DISMISS_WAIT, FINAL_VALIDATION -> 1;
                  default -> 0;
                }))
        .click(any(Locator.ClickOptions.class));
    verify(page, never()).waitForTimeout(anyDouble());
  }

  @Test
  void iframeDismissTimeoutRetainsDismissWaitThroughSafetyCheckAndHandleCleanup() {
    when(page.url()).thenReturn(VulcanPrivacyConsentFrameTest.PAGE_URL);
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    doNothing().when(consent.accept()).click(any(Locator.ClickOptions.class));
    assertThatThrownBy(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls, operations::add))
        .isInstanceOf(TimeoutError.class);
    assertThat(operations)
        .endsWith(PrivacyConsentOperation.DISMISS_WAIT)
        .doesNotContain(PrivacyConsentOperation.COMPLETED);
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
    verify(consent.owner()).dispose();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void observerReceivesOnlyEnumsAndItsExceptionsCannotChangeSuccessOrFailure(boolean failConsent) {
    var consent = VulcanPrivacyConsentTest.knownConsent(page);
    var browserFailure = new PlaywrightException("original private exception");
    if (failConsent)
      doThrow(browserFailure).when(consent.accept()).click(any(Locator.ClickOptions.class));
    Runnable dismiss =
        () ->
            VulcanPrivacyConsent.dismissIfPresent(
                page,
                urls,
                operation -> {
                  assertThat(operation).isIn((Object[]) PrivacyConsentOperation.values());
                  operations.add(operation);
                  throw new IllegalStateException(
                      "SUPER_SECRET_PASSWORD SUPER_SECRET_COOKIE "
                          + "SECRET_TENANT_PATH SECRET_APPGUID https://external.example/private");
                });
    if (failConsent) {
      assertThatThrownBy(dismiss::run).isSameAs(browserFailure);
      assertThat(operations)
          .endsWith(PrivacyConsentOperation.ACCEPT_CLICK)
          .doesNotContain(PrivacyConsentOperation.COMPLETED);
    } else {
      assertThatCode(dismiss::run).doesNotThrowAnyException();
      assertThat(operations).endsWith(PrivacyConsentOperation.COMPLETED);
      verify(consent.originalContainer())
          .waitForElementState(
              eq(ElementState.HIDDEN), any(ElementHandle.WaitForElementStateOptions.class));
    }
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
    verify(consent.originalContainer()).dispose();
    verify(page).offFrameNavigated(any());
  }

  @Test
  void formatterAllowsOnlyFiniteValuesAndOmitsStaleConsentOutsideConsentStages() {
    for (var stage : BrowserAuthStage.values()) {
      if (stage == BrowserAuthStage.SESSION_CAPTURE)
        continue; // Separate capture formatter needs its observation.
      for (var operation : PrivacyConsentOperation.values()) {
        for (var category : VulcanAuthFailureCategory.values()) {
          String message =
              PlaywrightVulcanBrowserAuthenticator.formatFailure(
                  stage, operation, PrivacyConsentDismissObservation.NONE, category);
          boolean consent =
              stage == BrowserAuthStage.INITIAL_PORTAL_CONSENT
                  || stage == BrowserAuthStage.POST_DIRECT_LOGIN_CONSENT;
          assertThat(message)
              .isEqualTo(
                  "VULCAN browser authentication failed: stage="
                      + stage.name()
                      + (consent ? " consentOperation=" + operation.name() : "")
                      + (consent && operation == PrivacyConsentOperation.DISMISS_WAIT
                          ? " dismissFailure=NOT_APPLICABLE dismissState=UNAVAILABLE"
                              + " headingPresent=UNAVAILABLE containerVisible=UNAVAILABLE anyOwnerAriaHidden=UNAVAILABLE"
                          : "")
                      + " category="
                      + category.name());
          assertThat(message)
              .matches(
                  "VULCAN browser authentication failed: stage=[A-Z_]+"
                      + "( consentOperation=[A-Z_]+)?"
                      + "( dismissFailure=[A-Z_]+ dismissState=[A-Z_]+ headingPresent=[A-Z_]+"
                      + " containerVisible=[A-Z_]+ anyOwnerAriaHidden=[A-Z_]+)? category=[A-Z_]+");
        }
      }
    }
  }

  @Test
  void finiteDiagnosticVocabularyAndOriginalTimeoutsAreExplicit() {
    assertThat(BrowserAuthStage.values())
        .extracting(Enum::name)
        .containsExactly(
            "INITIAL_NAVIGATION",
            "INITIAL_PORTAL_CONSENT",
            "DIRECT_LOGIN_DISCOVERY",
            "DIRECT_LOGIN_NAVIGATION",
            "POST_DIRECT_LOGIN_CONSENT",
            "LOGIN_FORM_VALIDATION",
            "CREDENTIAL_SUBMISSION",
            "POST_LOGIN_VALIDATION",
            "SESSION_CAPTURE");
    assertThat(PrivacyConsentOperation.values())
        .extracting(Enum::name)
        .containsExactly(
            "NOT_STARTED",
            "DISCOVERY",
            "TRUST_VALIDATION",
            "ACTION_RESOLUTION",
            "ACCEPT_CLICK",
            "DISMISS_WAIT",
            "FINAL_VALIDATION",
            "COMPLETED");
    assertThat(VulcanPrivacyConsent.DISCOVERY_TIMEOUT_MS).isEqualTo(2_000);
    assertThat(VulcanPrivacyConsent.DISMISS_TIMEOUT_MS).isEqualTo(3_000);
  }

  private void driveCondition() {
    doAnswer(
            invocation -> {
              BooleanSupplier condition = invocation.getArgument(0);
              if (!condition.getAsBoolean()) throw new TimeoutError("synthetic deadline");
              return null;
            })
        .when(page)
        .waitForCondition(any(), any(Page.WaitForConditionOptions.class));
  }
}
