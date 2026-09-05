package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.ElementState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import java.io.StringReader;
import java.net.URI;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

class VulcanPrivacyConsentTest {
  private static final String URL = "https://school.vulcan.net.pl/login";
  private final Page page = mock(Page.class, RETURNS_DEEP_STUBS);
  private final PortalUrlValidator urls = new PortalUrlValidator();

  @Test
  void recognizesOnlyTheKnownNormalizedPrivacyHeadingAndAcceptance() {
    assertThat(VulcanPrivacyConsent.HEADING.flags()).isZero();
    assertThat(
            VulcanPrivacyConsent.HEADING.matcher("  Szanujemy\nTwoją\u00a0prywatność ").matches())
        .isTrue();
    assertThat(VulcanPrivacyConsent.HEADING.matcher("Ustawienia prywatności").matches()).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "<button>Zgadzam się</button>",
        "<input type='button' value='Zgadzam się'/>",
        "<input type='submit' value='Zgadzam się'/>",
        "<div role='button'>Zgadzam się</div>",
        "<div tabindex='0'> Zgadzam&#160;się </div>",
        "<span>Zgadzam się</span>",
        "<button><span>Zgadzam się</span></button>"
      })
  void exactScopedLabelRecognizesNativeAndRolelessActionsWithoutDuplicateNestedLabels(String action)
      throws Exception {
    Node container =
        privacyContainer(
            "<div role='dialog'><h2>Szanujemy Twoją prywatność</h2>"
                + "<span>Przejdź do ustawień</span>"
                + action
                + "</div><span>Zgadzam się</span>",
            true);
    assertThat(select(container, VulcanPrivacyConsent.ACCEPT_CANDIDATES).getLength()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Przejdź do ustawień",
        "Zaloguj się",
        "Zgadzam się na zmianę hasła",
        "Nie zgadzam się",
        "Accept"
      })
  void unrelatedAndSettingsLabelsAreNotSelected(String text) throws Exception {
    Node container =
        privacyContainer(
            "<div role='dialog'><h2>Szanujemy Twoją prywatność</h2><span>" + text + "</span></div>",
            true);
    assertThat(select(container, VulcanPrivacyConsent.ACCEPT_CANDIDATES).getLength()).isZero();
  }

  @Test
  void nonAriaContainerUsesTheKnownSettingsActionAsItsIndependentBoundary() throws Exception {
    Node container =
        privacyContainer(
            "<div id='privacy'><div><h2>Szanujemy Twoją prywatność</h2></div>"
                + "<div><span>Przejdź do ustawień</span><span>Zgadzam się</span></div></div><button>Zgadzam się</button>",
            false);
    assertThat(container.getAttributes().getNamedItem("id").getNodeValue()).isEqualTo("privacy");
    assertThat(select(container, VulcanPrivacyConsent.ACCEPT_CANDIDATES).getLength()).isEqualTo(1);
  }

  @Test
  void anOutsideAcceptanceLabelCannotSupplyAMissingDialogAction() throws Exception {
    Node container =
        privacyContainer(
            "<div role='dialog'><h2>Szanujemy Twoją prywatność</h2>"
                + "<span>Przejdź do ustawień</span></div><button>Zgadzam się</button>",
            true);
    assertThat(select(container, VulcanPrivacyConsent.ACCEPT_CANDIDATES).getLength()).isZero();
  }

  @Test
  void duplicateLabelsRemainAmbiguousAndNestedHyperlinksRemainUnsafe() throws Exception {
    Node container =
        privacyContainer(
            "<div role='dialog'><h2>Szanujemy Twoją prywatność</h2>"
                + "<button>Zgadzam się</button><a href='https://external.example/'><span>Zgadzam się</span></a></div>",
            true);
    NodeList candidates = select(container, VulcanPrivacyConsent.ACCEPT_CANDIDATES);
    assertThat(candidates.getLength()).isEqualTo(2);
    assertThat(select(candidates.item(1), VulcanPrivacyConsent.UNSAFE_ANCESTOR).getLength())
        .isEqualTo(1);
  }

  @Test
  void absentConsentUsesOnlyTheBoundedReadinessWait() {
    when(page.url()).thenReturn(URL);
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(page, never()).locator(anyString());
    verify(page, never()).waitForTimeout(anyDouble());
    verify(page, never()).getByRole(any(), any(Page.GetByRoleOptions.class));
    verify(page).waitForCondition(any(), argThat(options -> options.timeout == 2_000));
  }

  @Test
  void clicksTheScopedLabelAndWaitsForTheOriginalContainerBoundedly() {
    ConsentLocators consent = knownConsent(page);
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    ArgumentCaptor<Locator.ClickOptions> click =
        ArgumentCaptor.forClass(Locator.ClickOptions.class);
    ArgumentCaptor<ElementHandle.WaitForElementStateOptions> wait =
        ArgumentCaptor.forClass(ElementHandle.WaitForElementStateOptions.class);
    var order = inOrder(consent.accept(), consent.originalContainer());
    order.verify(consent.accept()).click(click.capture());
    order
        .verify(consent.originalContainer())
        .waitForElementState(eq(ElementState.HIDDEN), wait.capture());
    assertThat(click.getValue().timeout).isEqualTo(3_000);
    assertThat(wait.getValue().timeout).isEqualTo(3_000);
    verify(consent.originalContainer()).dispose();
    verify(page, never()).frameLocator(anyString());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 2})
  void unresolvedOrAmbiguousConsentFailsClosedWithoutClicking(int count) {
    ConsentLocators consent = knownConsent(page);
    when(consent.candidates().count()).thenReturn(count);
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void knownHeadingWithoutAnIdentifiableContainerFailsClosed() {
    ConsentLocators consent = knownConsent(page);
    when(consent.container().count()).thenReturn(0);
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
  }

  @Test
  void unsafeHyperlinkCandidateIsNotFollowed() {
    ConsentLocators consent = knownConsent(page);
    when(consent.accept().locator(VulcanPrivacyConsent.UNSAFE_ANCESTOR).count()).thenReturn(1);
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void offDomainPageIsRejectedBeforeInspectingConsent() {
    when(page.url()).thenReturn("https://external.example/login");
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(page, never()).getByText(any(java.util.regex.Pattern.class));
  }

  @Test
  void aDialogThatDoesNotDismissFailsEvenWhenItsHeadingWasRemoved() {
    ConsentLocators consent = knownConsent(page);
    doThrow(new TimeoutError("synthetic timeout"))
        .when(consent.originalContainer())
        .waitForElementState(
            eq(ElementState.HIDDEN), any(ElementHandle.WaitForElementStateOptions.class));
    assertThatThrownBy(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls))
        .isInstanceOf(TimeoutError.class);
    verify(consent.originalContainer()).dispose();
  }

  @Test
  void ordinarySamePageConsentFormIsAllowedBeforeCredentials() {
    ConsentLocators consent = knownConsent(page);
    when(consent.accept().evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR))
        .thenReturn(form(URL, "_self", false));
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.accept()).click(any(Locator.ClickOptions.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://external.example/",
        "http://school.vulcan.net.pl/login",
        "https://school.vulcan.net.pl/other",
        "https://school.vulcan.net.pl:8443/login",
        "https://user:pass@school.vulcan.net.pl/login"
      })
  void unsafeOrDifferentPageFormSubmissionIsNotClicked(String action) {
    ConsentLocators consent = knownConsent(page);
    when(consent.accept().evaluate(VulcanPrivacyConsent.FORM_BEHAVIOR))
        .thenReturn(form(action, "", false));
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void consentFormCannotSubmitCredentialsOrOpenAnotherContext() {
    assertThat(
            VulcanPrivacyConsent.isSafeConsentForm(URI.create(URL), form(URL, "_self", true), urls))
        .isFalse();
    assertThat(
            VulcanPrivacyConsent.isSafeConsentForm(
                URI.create(URL), form(URL, "_blank", false), urls))
        .isFalse();
    assertThat(
            VulcanPrivacyConsent.isSafeConsentForm(
                URI.create(URL), form(URL + "#consent", "", false), urls))
        .isTrue();
  }

  @Test
  void dialogMethodCanDismissWithoutNavigationOnlyOnAnAllowedPage() {
    var dialog = Map.of("action", "", "target", "", "method", "dialog", "credentials", false);
    assertThat(VulcanPrivacyConsent.isSafeConsentForm(URI.create(URL), dialog, urls)).isTrue();
    assertThat(
            VulcanPrivacyConsent.isSafeConsentForm(
                URI.create("https://external.example/"), dialog, urls))
        .isFalse();
  }

  @Test
  void navigationOutsideVulcanAfterConsentIsRejected() {
    ConsentLocators consent = knownConsent(page);
    doAnswer(
            invocation -> {
              when(page.url()).thenReturn("https://external.example/");
              return null;
            })
        .when(consent.originalContainer())
        .waitForElementState(
            eq(ElementState.HIDDEN), any(ElementHandle.WaitForElementStateOptions.class));
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
    verify(consent.originalContainer()).dispose();
  }

  @Test
  void replacementVisiblePrivacyHeadingDoesNotCountAsSuccessfulDismissal() {
    ConsentLocators consent = knownConsent(page);
    doNothing().when(consent.accept()).click(any(Locator.ClickOptions.class));
    assertUnsupported(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls));
  }

  private static Map<String, Object> form(String action, String target, boolean credentials) {
    return Map.of("action", action, "target", target, "method", "post", "credentials", credentials);
  }

  static ConsentLocators knownConsent(Page page) {
    when(page.url()).thenReturn(URL);
    return knownConsent(page.getByText(VulcanPrivacyConsent.HEADING));
  }

  static ConsentLocators knownConsent(Frame frame) {
    return knownConsent(frame.getByText(VulcanPrivacyConsent.HEADING));
  }

  private static ConsentLocators knownConsent(Locator unfilteredHeadings) {
    Locator headings = mock(Locator.class);
    Locator heading = mock(Locator.class, RETURNS_DEEP_STUBS);
    Locator container = mock(Locator.class, RETURNS_DEEP_STUBS);
    Locator candidates = mock(Locator.class);
    Locator accept = mock(Locator.class, RETURNS_DEEP_STUBS);
    ElementHandle original = mock(ElementHandle.class);
    when(unfilteredHeadings.filter(any(Locator.FilterOptions.class))).thenReturn(headings);
    when(headings.count()).thenReturn(1);
    when(headings.first()).thenReturn(heading);
    when(heading.locator(VulcanPrivacyConsent.DIALOG)).thenReturn(container);
    when(container.count()).thenReturn(1);
    when(container.isVisible()).thenReturn(true);
    when(container
            .locator(VulcanPrivacyConsent.ACCEPT_CANDIDATES)
            .filter(any(Locator.FilterOptions.class)))
        .thenReturn(candidates);
    when(candidates.count()).thenReturn(1);
    when(candidates.first()).thenReturn(accept);
    when(accept.isVisible()).thenReturn(true);
    when(container.elementHandle(any(Locator.ElementHandleOptions.class))).thenReturn(original);
    doAnswer(
            invocation -> {
              when(headings.count()).thenReturn(0);
              return null;
            })
        .when(accept)
        .click(any(Locator.ClickOptions.class));
    return new ConsentLocators(container, candidates, accept, original);
  }

  private static void assertUnsupported(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            exception ->
                assertThat(exception.category())
                    .isEqualTo(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW));
  }

  // Exercise the production XPath selectors against synthetic markup with the JDK's XPath engine.
  // No browser, captured HTML, external entities, or network access is involved.
  private static Node privacyContainer(String markup, boolean semantic) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    var document =
        factory
            .newDocumentBuilder()
            .parse(new InputSource(new StringReader("<html><body>" + markup + "</body></html>")));
    Node heading = select(document, "xpath=//h2").item(0);
    NodeList containers =
        select(
            heading,
            semantic ? VulcanPrivacyConsent.DIALOG : VulcanPrivacyConsent.FALLBACK_CONTAINER);
    assertThat(containers.getLength()).isEqualTo(1);
    return containers.item(0);
  }

  private static NodeList select(Node context, String selector) throws Exception {
    return (NodeList)
        XPathFactory.newInstance()
            .newXPath()
            .evaluate(selector.substring("xpath=".length()), context, XPathConstants.NODESET);
  }

  record ConsentLocators(
      Locator container, Locator candidates, Locator accept, ElementHandle originalContainer) {}
}
