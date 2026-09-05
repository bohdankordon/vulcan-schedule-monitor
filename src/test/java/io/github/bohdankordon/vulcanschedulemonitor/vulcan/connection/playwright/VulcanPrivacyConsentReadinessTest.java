package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic lifecycle transitions drive the real condition; no browser or real-time waiting. */
class VulcanPrivacyConsentReadinessTest {
  private final Page page = mock(Page.class, RETURNS_DEEP_STUBS);
  private final Frame main = mock(Frame.class);
  private final PortalUrlValidator urls = new PortalUrlValidator();
  private final AtomicReference<Consumer<Frame>> navigation = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    when(page.url()).thenReturn(VulcanPrivacyConsentFrameTest.PAGE_URL);
    when(page.mainFrame()).thenReturn(main);
    when(main.url()).thenReturn(VulcanPrivacyConsentFrameTest.PAGE_URL);
    when(page.frames()).thenReturn(List.of(main));
    doAnswer(
            invocation -> {
              navigation.set(invocation.getArgument(0));
              return null;
            })
        .when(page)
        .onFrameNavigated(any());
  }

  @AfterEach
  void listenersAndSleepPolicy() {
    verify(page).offFrameNavigated(same(navigation.get()));
    // Current frame snapshots are re-evaluated by Playwright's condition wait, so attach/detach
    // listeners are unnecessary. The navigation listener preserves trust-loss history only.
    verify(page, never()).onFrameAttached(any());
    verify(page, never()).onFrameDetached(any());
    verify(page, never()).waitForTimeout(anyDouble());
  }

  @Test
  void opaqueThenAllowedThenHeadingThenActionUsesExistingDismissal() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    Locator headings = headings(consent.frame());
    when(consent.frame().url()).thenReturn("");
    when(headings.count()).thenReturn(0);
    when(consent.candidates().count()).thenReturn(0);
    clearInvocations(consent.frame());
    drive(
        () -> {
          verify(consent.frame(), never()).frameElement();
          verify(consent.frame(), never()).getByText(any(Pattern.class));
          navigate(consent.frame(), VulcanPrivacyConsentFrameTest.FRAME_URL);
        },
        () -> {
          verify(consent.frame()).getByText(any(Pattern.class));
          when(headings.count()).thenReturn(1);
        },
        () -> {
          verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
          when(consent.candidates().count()).thenReturn(1);
        });
    dismiss();
    verify(consent.accept()).evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR);
    verify(consent.accept()).click(argThat(options -> options.timeout == 3_000));
    assertThat(consent.owner().isVisible()).isFalse();
    verify(page).waitForCondition(any(), argThat(options -> options.timeout == 2_000));
    verify(page).waitForCondition(any(), argThat(options -> options.timeout == 3_000));
  }

  @Test
  void aFrameAttachedAfterInitialSnapshotIsReconsideredWhenItCommits() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(page.frames()).thenReturn(List.of(main));
    when(consent.frame().url()).thenReturn("");
    clearInvocations(consent.frame());
    drive(
        () -> when(page.frames()).thenReturn(List.of(main, consent.frame())),
        () -> {
          verify(consent.frame(), never()).getByText(any(Pattern.class));
          navigate(consent.frame(), VulcanPrivacyConsentFrameTest.FRAME_URL);
        });
    dismiss();
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
    verify(page, atLeast(3)).frames();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://external.example/privacy",
        "http://school.vulcan.net.pl/privacy",
        "https://localhost/privacy",
        "https://127.0.0.1/privacy",
        "https://[::1]/privacy",
        "https://school.vulcan.net.pl:8443/privacy",
        "https://user:pass@school.vulcan.net.pl/privacy",
        "not a uri",
        "about:blank"
      })
  void initiallyOpaqueFrameWithUntrustedDestinationNeverReceivesDomInspection(String destination) {
    Frame frame = mock(Frame.class);
    when(frame.url()).thenReturn("");
    when(frame.parentFrame()).thenReturn(main);
    when(page.frames()).thenReturn(List.of(main, frame));
    drive(() -> navigate(frame, destination));
    dismiss();
    verify(frame, never()).frameElement();
    verify(frame, never()).getByText(any(Pattern.class));
    verify(frame, never()).locator(anyString());
  }

  @Test
  void anUnrelatedNonblockingFrameNavigatingExternalDoesNotBecomeAKnownConsentFailure() {
    Frame frame = mock(Frame.class, RETURNS_DEEP_STUBS);
    when(frame.url()).thenReturn(VulcanPrivacyConsentFrameTest.FRAME_URL);
    when(frame.parentFrame()).thenReturn(main);
    when(frame.frameElement().isVisible()).thenReturn(false);
    when(page.frames()).thenReturn(List.of(main, frame));
    drive(
        () -> {
          navigate(frame, "https://external.example/widget");
          clearInvocations(frame);
        });
    dismiss();
    verify(frame, never()).frameElement();
    verify(frame, never()).getByText(any(Pattern.class));
  }

  @Test
  void anInitiallyOpaqueUnidentifiedFrameCannotCauseAPermanentWait() {
    Frame frame = mock(Frame.class);
    when(frame.url()).thenReturn("");
    when(page.frames()).thenReturn(List.of(main, frame));
    drive();
    dismiss();
    verify(frame, never()).frameElement();
    verify(page).waitForCondition(any(), argThat(options -> options.timeout == 2_000));
  }

  @Test
  void ancestorLeavingBoundaryWhileWaitingFailsClosedEvenAfterReturning() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(consent.candidates().count()).thenReturn(0);
    drive(
        () -> {
          navigate(main, "https://external.example/");
          navigate(main, VulcanPrivacyConsentFrameTest.PAGE_URL);
        });
    assertUnsupported();
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void identifiedPrivacyFrameReturningToAnOpaqueUrlIsNotForgotten() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(consent.candidates().count()).thenReturn(0);
    drive(() -> navigate(consent.frame(), ""));
    assertUnsupported();
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void aKnownHeadingDisappearingInsideAStillVisibleOwnerRemainsUnresolved() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    Locator headings = headings(consent.frame());
    when(consent.candidates().count()).thenReturn(0);
    drive(() -> when(headings.count()).thenReturn(0));
    assertUnsupported();
    assertThat(consent.owner().isVisible()).isTrue();
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void knownHeadingWithoutAnActionFailsAtTheDeadline() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(consent.candidates().count()).thenReturn(0);
    drive();
    assertUnsupported();
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void aSecondKnownSurfaceAppearingDuringReadinessFailsClosed() {
    var first = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    var second = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(first.candidates().count()).thenReturn(0);
    when(page.frames()).thenReturn(List.of(main, first.frame()));
    drive(() -> when(page.frames()).thenReturn(List.of(main, first.frame(), second.frame())));
    assertUnsupported();
    verify(first.accept(), never()).click(any(Locator.ClickOptions.class));
    verify(second.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void actionAmbiguityAppearingDuringReadinessFailsClosed() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(consent.candidates().count()).thenReturn(0);
    drive(() -> when(consent.candidates().count()).thenReturn(2));
    assertUnsupported();
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void normalDetachmentOfPendingConsentDoesNotInspectStaleDom() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(consent.candidates().count()).thenReturn(0);
    drive(
        () -> {
          when(consent.frame().isDetached()).thenReturn(true);
          when(page.frames()).thenReturn(List.of(main));
          clearInvocations(consent.frame(), consent.owner());
        });
    dismiss();
    verify(consent.frame(), never()).getByText(any(Pattern.class));
    verify(consent.owner(), never()).isVisible();
    verify(consent.owner()).dispose();
  }

  @Test
  void disappearingOwnerWhileAnActionIsLoadingRemovesTheKnownBlocker() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(consent.candidates().count()).thenReturn(0);
    drive(() -> when(consent.owner().isVisible()).thenReturn(false));
    dismiss();
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void noConsentStillPerformsAFinalReevaluationAtTheDeadline() {
    drive();
    dismiss();
    verify(page, times(2)).frames();
    verify(page).waitForCondition(any(), argThat(options -> options.timeout == 2_000));
    verify(page, never()).locator(anyString());
  }

  @Test
  void consentBecomingReadyAtTheDeadlineIsHandledByTheFinalScan() {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(consent.frame().url()).thenReturn("");
    doAnswer(
            invocation -> {
              BooleanSupplier condition = invocation.getArgument(0);
              Page.WaitForConditionOptions options = invocation.getArgument(1);
              if (options.timeout == VulcanPrivacyConsent.DISCOVERY_TIMEOUT_MS) {
                assertThat(condition.getAsBoolean()).isFalse();
                navigate(consent.frame(), VulcanPrivacyConsentFrameTest.FRAME_URL);
                throw new TimeoutError("synthetic discovery deadline");
              }
              assertThat(condition.getAsBoolean()).isTrue();
              return null;
            })
        .when(page)
        .waitForCondition(any(), any(Page.WaitForConditionOptions.class));
    dismiss();
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void detachmentDuringFinalActionResolutionIsNotAnUnresolvedBlocker(boolean throwsException) {
    var consent = VulcanPrivacyConsentFrameTest.knownFrameConsent(page);
    when(consent.candidates().count()).thenReturn(0);
    doAnswer(
            invocation -> {
              BooleanSupplier condition = invocation.getArgument(0);
              assertThat(condition.getAsBoolean()).isFalse();
              when(consent.candidates().count()).thenReturn(1);
              throw new TimeoutError("synthetic deadline");
            })
        .when(page)
        .waitForCondition(any(), any(Page.WaitForConditionOptions.class));
    when(consent.accept().evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR))
        .thenAnswer(
            invocation -> {
              when(consent.frame().isDetached()).thenReturn(true);
              if (throwsException) throw new PlaywrightException("synthetic detached context");
              return null;
            });
    dismiss();
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void browserFailureInsideAConditionIsNeverMistakenForTheDiscoveryDeadline(boolean timeout) {
    RuntimeException failure =
        timeout
            ? new TimeoutError("synthetic private browser detail")
            : new PlaywrightException("synthetic private browser detail");
    when(page.getByText(VulcanPrivacyConsent.HEADING)
            .filter(any(Locator.FilterOptions.class))
            .count())
        .thenThrow(failure);
    drive();
    assertThatThrownBy(this::dismiss).isSameAs(failure);
  }

  @Test
  void failureFromTheWaitItselfAlsoRemovesTheListener() {
    doThrow(new PlaywrightException("synthetic closed page"))
        .when(page)
        .waitForCondition(any(), any(Page.WaitForConditionOptions.class));
    assertThatThrownBy(this::dismiss).isInstanceOf(PlaywrightException.class);
  }

  @Test
  void topLevelHeadingCanWaitForItsContainerAndAcceptAction() {
    var consent = VulcanPrivacyConsentTest.knownConsent(page);
    when(consent.container().count()).thenReturn(0);
    when(consent.candidates().count()).thenReturn(0);
    drive(
        () -> when(consent.container().count()).thenReturn(1),
        () -> when(consent.candidates().count()).thenReturn(1));
    dismiss();
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
    verify(consent.originalContainer())
        .waitForElementState(
            eq(com.microsoft.playwright.options.ElementState.HIDDEN),
            any(ElementHandle.WaitForElementStateOptions.class));
  }

  private Locator headings(Frame frame) {
    return frame
        .getByText(VulcanPrivacyConsent.HEADING)
        .filter(new Locator.FilterOptions().setVisible(true));
  }

  private void navigate(Frame frame, String destination) {
    when(frame.url()).thenReturn(destination);
    navigation.get().accept(frame);
  }

  private void drive(Runnable... transitions) {
    doAnswer(
            invocation -> {
              BooleanSupplier condition = invocation.getArgument(0);
              Page.WaitForConditionOptions options = invocation.getArgument(1);
              if (options.timeout == VulcanPrivacyConsent.DISCOVERY_TIMEOUT_MS) {
                for (Runnable transition : transitions) {
                  if (condition.getAsBoolean()) return null;
                  transition.run();
                }
              }
              if (!condition.getAsBoolean()) throw new TimeoutError("synthetic condition deadline");
              return null;
            })
        .when(page)
        .waitForCondition(any(), any(Page.WaitForConditionOptions.class));
  }

  private void dismiss() {
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
  }

  private void assertUnsupported() {
    assertThatThrownBy(this::dismiss)
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            failure ->
                assertThat(failure.category())
                    .isEqualTo(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW));
  }
}
