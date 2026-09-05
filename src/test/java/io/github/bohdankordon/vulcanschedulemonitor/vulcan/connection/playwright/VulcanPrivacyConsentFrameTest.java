package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VulcanPrivacyConsentFrameTest {
  static final String PAGE_URL = "https://school.vulcan.net.pl/synthetic/login";
  static final String FRAME_URL = "https://school.vulcan.net.pl/synthetic/privacy";
  private final Page page = mock(Page.class, RETURNS_DEEP_STUBS);
  private final Frame main = mock(Frame.class);
  private final PortalUrlValidator urls = new PortalUrlValidator();

  @BeforeEach
  void setUp() {
    when(page.url()).thenReturn(PAGE_URL);
    when(page.mainFrame()).thenReturn(main);
    when(main.url()).thenReturn(PAGE_URL);
    when(page.frames()).thenReturn(List.of(main));
  }

  @Test
  void soleAllowedFrameConsentUsesTheSharedPolicyAndWaitsForItsOwner() {
    var consent = knownFrameConsent(page);
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.accept()).evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR);
    verify(consent.accept())
        .click(argThat(options -> options.timeout == 3_000 && !Boolean.TRUE.equals(options.force)));
    verify(page, times(2))
        .waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
    verify(page)
        .waitForCondition(any(BooleanSupplier.class), argThat(options -> options.timeout == 3_000));
    verify(consent.owner()).dispose();
    verify(consent.frame(), never()).locator(anyString());
    assertThat(consent.frame().isDetached()).isFalse();
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
        "https://vulcan.net.pl.external.example/privacy",
        "about:blank",
        "data:text/html,privacy",
        "not a uri"
      })
  void ineligibleFrameIsExcludedBeforeAnyDomOrOwnerInspection(String url) {
    Frame external = mock(Frame.class);
    when(external.url()).thenReturn(url);
    when(external.parentFrame()).thenReturn(main);
    when(page.frames()).thenReturn(List.of(main, external));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(external, never()).frameElement();
    verify(external, never()).getByText(any(Pattern.class));
    verify(external, never()).locator(anyString());
    verify(page)
        .waitForCondition(any(BooleanSupplier.class), argThat(options -> options.timeout == 2_000));
  }

  @Test
  void allowedConsentAndExternalFrameOnlyInspectAllowedContent() {
    var consent = knownFrameConsent(page);
    Frame external = mock(Frame.class);
    when(external.url()).thenReturn("https://external.example/privacy");
    when(page.frames()).thenReturn(List.of(main, external, consent.frame()));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(external, never()).getByText(any(Pattern.class));
    verify(external, never()).frameElement();
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void allowedChildUnderExternalAncestorIsNeverInspected() {
    Frame parent = mock(Frame.class);
    Frame child = mock(Frame.class);
    when(parent.url()).thenReturn("https://external.example/");
    when(parent.parentFrame()).thenReturn(main);
    when(child.url()).thenReturn(FRAME_URL);
    when(child.parentFrame()).thenReturn(parent);
    when(page.frames()).thenReturn(List.of(main, child));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(child, never()).frameElement();
    verify(child, never()).getByText(any(Pattern.class));
    verify(parent, never()).frameElement();
  }

  @Test
  void hiddenIframeIsNotInspected() {
    var consent = knownFrameConsent(page);
    when(consent.owner().isVisible()).thenReturn(false);
    clearInvocations(consent.frame());
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.frame(), never()).getByText(any(Pattern.class));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void twoKnownFramesFailClosedBeforeEitherClick() {
    var first = knownFrameConsent(page);
    var second = knownFrameConsent(page);
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(first.accept(), never()).click(any(Locator.ClickOptions.class));
    verify(second.accept(), never()).click(any(Locator.ClickOptions.class));
    verify(first.owner()).dispose();
    verify(second.owner()).dispose();
  }

  @Test
  void simultaneousTopLevelAndFrameConsentIsAmbiguous() {
    var top = VulcanPrivacyConsentTest.knownConsent(page);
    var frame = knownFrameConsent(page);
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(top.accept(), never()).click(any(Locator.ClickOptions.class));
    verify(frame.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void knownFrameWithUnresolvedActionFailsClosed(int count) {
    var consent = knownFrameConsent(page);
    when(consent.candidates().count()).thenReturn(count);
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void frameHyperlinkAndCredentialBearingFormRetainSharedRejection() {
    var consent = knownFrameConsent(page);
    when(consent.accept().locator(VulcanPrivacyConsent.UNSAFE_ANCESTOR).count()).thenReturn(1);
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    when(consent.accept().locator(VulcanPrivacyConsent.UNSAFE_ANCESTOR).count()).thenReturn(0);
    when(consent.accept().evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR))
        .thenReturn(
            Map.of("action", FRAME_URL, "target", "", "method", "post", "credentials", true));
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void consentFormUsesFrameUriRatherThanPageUri() {
    var consent = knownFrameConsent(page);
    when(consent.accept().evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR))
        .thenReturn(
            Map.of("action", PAGE_URL, "target", "", "method", "post", "credentials", false));
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    when(consent.accept().evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR))
        .thenReturn(
            Map.of("action", FRAME_URL, "target", "", "method", "post", "credentials", false));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void frameDetachmentAfterClickDoesNotInspectStaleDom() {
    var consent = knownFrameConsent(page);
    doAnswer(
            invocation -> {
              when(consent.frame().isDetached()).thenReturn(true);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    // Owner visibility is read only during eligibility, never after detachment.
    verify(consent.owner(), times(1)).isVisible();
  }

  @Test
  void detachmentWhileClickIsFinishingIsSuccessfulDismissal() {
    var consent = knownFrameConsent(page);
    doAnswer(
            invocation -> {
              when(consent.frame().isDetached()).thenReturn(true);
              throw new PlaywrightException("synthetic detached frame " + FRAME_URL);
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.owner(), times(1)).isVisible();
  }

  @Test
  void visibleOwnerStillBlocksEvenIfInnerConsentWasRemoved() {
    var consent = knownFrameConsent(page);
    doNothing().when(consent.accept()).click(any(Locator.ClickOptions.class));
    assertThatThrownBy(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls))
        .isInstanceOf(TimeoutError.class);
    verify(page)
        .waitForCondition(any(BooleanSupplier.class), argThat(options -> options.timeout == 3_000));
  }

  @Test
  void navigationOutsideBoundaryFailsEvenIfTheFrameThenDetaches() {
    var consent = knownFrameConsent(page);
    AtomicReference<Consumer<Frame>> navigation = new AtomicReference<>();
    doAnswer(
            invocation -> {
              navigation.set(invocation.getArgument(0));
              return null;
            })
        .when(page)
        .onFrameNavigated(any());
    doAnswer(
            invocation -> {
              when(consent.frame().url()).thenReturn("https://external.example/");
              navigation.get().accept(consent.frame());
              when(consent.frame().isDetached()).thenReturn(true);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(page).offFrameNavigated(navigation.get());
  }

  @Test
  void frameDestinationIsRecheckedBeforeClick() {
    var consent = knownFrameConsent(page);
    when(consent.accept().evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR))
        .thenAnswer(
            invocation -> {
              when(consent.frame().url()).thenReturn("https://external.example/");
              return null;
            });
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void unsafeNavigationDuringDiscoveryCannotBeErasedByReturningToVulcan() {
    var consent = knownFrameConsent(page);
    AtomicReference<Consumer<Frame>> navigation = new AtomicReference<>();
    doAnswer(
            invocation -> {
              navigation.set(invocation.getArgument(0));
              return null;
            })
        .when(page)
        .onFrameNavigated(any());
    Locator headings =
        consent
            .frame()
            .getByText(VulcanPrivacyConsent.HEADING)
            .filter(new Locator.FilterOptions().setVisible(true));
    when(headings.count())
        .thenAnswer(
            invocation -> {
              when(consent.frame().url()).thenReturn("https://external.example/");
              navigation.get().accept(consent.frame());
              when(consent.frame().url()).thenReturn(FRAME_URL);
              navigation.get().accept(consent.frame());
              return 1;
            });
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void noKnownPrivacyInAllowedDocumentsCompletesAfterBoundedObservation() {
    Frame frame = mock(Frame.class, RETURNS_DEEP_STUBS);
    when(frame.url()).thenReturn(FRAME_URL);
    when(frame.parentFrame()).thenReturn(main);
    when(frame.frameElement().isVisible()).thenReturn(true);
    when(page.frames()).thenReturn(List.of(main, frame));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(page)
        .waitForCondition(any(BooleanSupplier.class), argThat(options -> options.timeout == 2_000));
    verify(page, never()).waitForTimeout(anyDouble());
  }

  @Test
  void nestedAllowedConsentCanBeDismissedByHidingItsAncestorOwner() {
    var consent = knownFrameConsent(page);
    Frame parent = mock(Frame.class, RETURNS_DEEP_STUBS);
    when(parent.url()).thenReturn("https://school.vulcan.net.pl/synthetic/wrapper");
    when(parent.parentFrame()).thenReturn(main);
    ElementHandle parentOwner = mock(ElementHandle.class);
    when(parentOwner.isVisible()).thenReturn(true);
    when(parent.frameElement()).thenReturn(parentOwner);
    when(consent.frame().parentFrame()).thenReturn(parent);
    when(page.frames()).thenReturn(List.of(main, parent, consent.frame()));
    doAnswer(
            invocation -> {
              when(parentOwner.isVisible()).thenReturn(false);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void ancestorNavigationOutsideBoundaryAfterClickFailsClosed() {
    var consent = knownFrameConsent(page);
    doAnswer(
            invocation -> {
              when(main.url()).thenReturn("https://external.example/");
              when(consent.owner().isVisible()).thenReturn(false);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
  }

  @Test
  void frameDetachingDuringOwnerDiscoveryIsIgnoredWithoutDomInspection() {
    var consent = knownFrameConsent(page);
    doAnswer(
            invocation -> {
              when(consent.frame().isDetached()).thenReturn(true);
              throw new PlaywrightException("synthetic detached owner");
            })
        .when(consent.frame())
        .frameElement();
    clearInvocations(consent.frame());
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.frame(), never()).getByText(any(Pattern.class));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  static FrameConsent knownFrameConsent(Page page) {
    Frame main = page.mainFrame();
    String pageUrl = page.url();
    when(main.url()).thenReturn(pageUrl);
    Frame frame = mock(Frame.class, RETURNS_DEEP_STUBS);
    when(frame.url()).thenReturn(FRAME_URL);
    when(frame.parentFrame()).thenReturn(main);
    ElementHandle owner = mock(ElementHandle.class);
    when(owner.isVisible()).thenReturn(true);
    when(frame.frameElement()).thenReturn(owner);
    var consent = VulcanPrivacyConsentTest.knownConsent(frame);
    List<Frame> frames = new ArrayList<>();
    for (Frame existing : page.frames()) frames.add(existing);
    if (!frames.contains(main)) frames.add(main);
    frames.add(frame);
    when(page.frames()).thenReturn(frames);
    doAnswer(
            invocation -> {
              when(owner.isVisible()).thenReturn(false);
              return null;
            })
        .when(consent.accept())
        .click(any(Locator.ClickOptions.class));
    doAnswer(
            invocation -> {
              BooleanSupplier condition = invocation.getArgument(0);
              if (!condition.getAsBoolean())
                throw new TimeoutError("synthetic bounded consent wait");
              return null;
            })
        .when(page)
        .waitForCondition(any(BooleanSupplier.class), any(Page.WaitForConditionOptions.class));
    clearInvocations(frame, owner);
    return new FrameConsent(frame, owner, consent.accept(), consent.candidates());
  }

  private static void assertUnsupported(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            exception ->
                assertThat(exception.category())
                    .isEqualTo(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW));
  }

  record FrameConsent(Frame frame, ElementHandle owner, Locator accept, Locator candidates) {}
}
