package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.ElementState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/** Playwright interfaces are mocked; these tests never install or start a browser. */
class PlaywrightVulcanBrowserAuthenticatorTest {
  private static final String URL = "https://school.vulcan.net.pl/synthetic/login?secret=query";
  private static final String USERNAME = "synthetic-login";
  private static final String PASSWORD = "synthetic-password";
  private static final String PASSWORD_SELECTOR =
      "input[autocomplete='current-password'], input[type='password']";
  private static final String USERNAME_SELECTOR =
      "input[autocomplete='username'], input[name='LoginName']";
  private static final String CAPTCHA_SELECTOR =
      "iframe[src*='captcha'], [class*='captcha'], [id*='captcha']";
  private static final String MFA_SELECTOR = "input[autocomplete='one-time-code']";

  private final Page page = mock(Page.class, RETURNS_DEEP_STUBS);
  private final BrowserContext context = mock(BrowserContext.class);
  private final Playwright playwright = mock(Playwright.class, RETURNS_DEEP_STUBS);
  private final Browser browser = mock(Browser.class);
  private final PlaywrightVulcanBrowserAuthenticator authenticator =
      new PlaywrightVulcanBrowserAuthenticator(new PortalUrlValidator(), true);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger =
      (Logger) LoggerFactory.getLogger(PlaywrightVulcanBrowserAuthenticator.class);
  private Locator username;
  private Locator password;
  private Locator submitter;

  @BeforeEach
  void setUp() {
    logs.start();
    logger.addAppender(logs);
    when(playwright.chromium().launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);
    when(browser.newContext()).thenReturn(context);
    when(context.newPage()).thenReturn(page);
    when(page.url()).thenReturn(URL);
    when(page.locator(USERNAME_SELECTOR).count()).thenReturn(1);
    Locator passwords = page.locator(PASSWORD_SELECTOR);
    when(passwords.count()).thenReturn(1);
    password = passwords.first();
    Locator forms = password.locator("xpath=ancestor::form[1]");
    when(forms.count()).thenReturn(1);
    Locator form = forms.first();
    Locator usernames = form.locator(USERNAME_SELECTOR);
    when(usernames.count()).thenReturn(1);
    username = usernames.first();
    Locator submitters =
        form.locator(
            "input[type='submit'], button[type='submit'], button:not([type]), input[type='image']");
    when(submitters.count()).thenReturn(1);
    submitter = submitters.first();
    when(form.evaluate("form => form.action")).thenReturn(URL);
    when(form.evaluate("form => form.method")).thenReturn("POST");
  }

  @AfterEach
  void tearDown() {
    logger.detachAppender(logs);
    logs.stop();
  }

  @Test
  void consentIsDismissedBeforeTheGuardAndEitherCredentialAndHeadlessRemainsEnabled() {
    var consent = VulcanPrivacyConsentTest.knownConsent(page);
    authenticateExpecting(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    var order =
        inOrder(
            consent.accept(), consent.originalContainer(), context, username, password, submitter);
    order.verify(consent.accept()).click(any(Locator.ClickOptions.class));
    order
        .verify(consent.originalContainer())
        .waitForElementState(
            eq(ElementState.HIDDEN), any(ElementHandle.WaitForElementStateOptions.class));
    order.verify(context).route(eq("**/*"), any());
    order.verify(username).fill(USERNAME);
    order.verify(password).fill(PASSWORD);
    order.verify(submitter).click();
    verify(playwright.chromium()).launch(argThat(options -> Boolean.TRUE.equals(options.headless)));
    assertFailureLog(BrowserAuthStage.SESSION_CAPTURE, VulcanAuthFailureCategory.PROTOCOL_FAILURE);
  }

  @Test
  void blockedConsentStopsBeforeCredentialEntryAndLogsOnlyTheStageAndCategory() {
    var consent = VulcanPrivacyConsentTest.knownConsent(page);
    doThrow(new TimeoutError(URL + " " + USERNAME + " " + PASSWORD))
        .when(consent.originalContainer())
        .waitForElementState(
            eq(ElementState.HIDDEN), any(ElementHandle.WaitForElementStateOptions.class));
    authenticateExpecting(VulcanAuthFailureCategory.TRANSIENT);
    verify(context, never()).route(anyString(), any());
    verify(username, never()).fill(anyString());
    verify(password, never()).fill(anyString());
    assertFailureLog(BrowserAuthStage.COOKIE_CONSENT, VulcanAuthFailureCategory.TRANSIENT);
  }

  @Test
  void safePostLoginFragmentReachesSessionCaptureAndMissingMaterialRemainsProtocolFailure() {
    doAnswer(
            invocation -> {
              when(page.url()).thenReturn(URL + "#schedule");
              return null;
            })
        .when(submitter)
        .click();
    authenticateExpecting(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    assertFailureLog(BrowserAuthStage.SESSION_CAPTURE, VulcanAuthFailureCategory.PROTOCOL_FAILURE);
  }

  @Test
  void completeSyntheticSessionIsCapturedAfterSafeHashNavigation() {
    String application = "https://school.vulcan.net.pl/synthetic/Dziennik.mvc/GetTree";
    AtomicReference<Consumer<Request>> observer = new AtomicReference<>();
    doAnswer(
            invocation -> {
              observer.set(invocation.getArgument(0));
              return null;
            })
        .when(page)
        .onRequest(any());
    Request observed = mock(Request.class);
    when(observed.url()).thenReturn(application);
    when(observed.headerValue("referer")).thenReturn(URL);
    when(observed.headerValue("x-v-requestverificationtoken")).thenReturn("synthetic-token");
    when(observed.headerValue("x-v-appguid")).thenReturn("synthetic-guid");
    when(context.cookies(application))
        .thenReturn(List.of(new Cookie("SyntheticCookie", "synthetic-value")));
    doAnswer(
            invocation -> {
              when(page.url()).thenReturn(URL + "#schedule");
              observer.get().accept(observed);
              return null;
            })
        .when(submitter)
        .click();
    try (var staticPlaywright = mockStatic(Playwright.class);
        var request = new VulcanLoginRequest(URI.create(URL), USERNAME, PASSWORD.toCharArray())) {
      staticPlaywright.when(Playwright::create).thenReturn(playwright);
      var session = authenticator.authenticate(request);
      assertThat(session.applicationBaseUri())
          .isEqualTo(URI.create("https://school.vulcan.net.pl/synthetic/"));
      assertThat(session.cookieHeader()).isEqualTo("SyntheticCookie=synthetic-value");
      assertThat(logs.list).isEmpty();
    }
  }

  @Test
  void directLoginDiscoveryFailureHasItsOwnStage() {
    when(page.locator(USERNAME_SELECTOR).count()).thenReturn(0);
    authenticateExpecting(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    assertFailureLog(
        BrowserAuthStage.DIRECT_LOGIN_DISCOVERY, VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
  }

  @Test
  void unresolvedKnownConsentFailsAtConsentWithoutAttemptingDirectLogin() {
    var consent = VulcanPrivacyConsentTest.knownConsent(page);
    when(consent.candidates().count()).thenReturn(0);
    clearInvocations(page);
    authenticateExpecting(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    verify(page, never()).locator(USERNAME_SELECTOR);
    verify(context, never()).route(anyString(), any());
    verify(username, never()).fill(anyString());
    verify(password, never()).fill(anyString());
    assertFailureLog(
        BrowserAuthStage.COOKIE_CONSENT, VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
  }

  @Test
  void directLoginClickFailureHasASanitizedNavigationStageAndExplicitNormalTimeout() {
    when(page.locator(USERNAME_SELECTOR).count()).thenReturn(0);
    Locator direct =
        page.locator("a[title*='nauczyciel'], a[title*='pracownik'], a[href*='LoginEndpoint.aspx']")
            .first();
    when(direct.count()).thenReturn(1);
    doThrow(new PlaywrightException(URL + " " + USERNAME + " " + PASSWORD))
        .when(direct)
        .click(any(Locator.ClickOptions.class));
    authenticateExpecting(VulcanAuthFailureCategory.TRANSIENT);
    verify(direct).click(argThat(options -> options.timeout == 30_000));
    assertFailureLog(BrowserAuthStage.DIRECT_LOGIN_NAVIGATION, VulcanAuthFailureCategory.TRANSIENT);
  }

  @Test
  void consentDismissalPrecedesDirectLoginNavigationAndCredentials() {
    var consent = VulcanPrivacyConsentTest.knownConsent(page);
    when(page.locator(USERNAME_SELECTOR).count()).thenReturn(0);
    Locator direct =
        page.locator("a[title*='nauczyciel'], a[title*='pracownik'], a[href*='LoginEndpoint.aspx']")
            .first();
    when(direct.count()).thenReturn(1);
    authenticateExpecting(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    var order = inOrder(consent.originalContainer(), direct, username, password);
    order
        .verify(consent.originalContainer())
        .waitForElementState(
            eq(ElementState.HIDDEN), any(ElementHandle.WaitForElementStateOptions.class));
    order.verify(direct).click(any(Locator.ClickOptions.class));
    order.verify(username).fill(USERNAME);
    order.verify(password).fill(PASSWORD);
  }

  @Test
  void unsafeLoginFormFailsBeforeCredentialEntryWithItsOwnStage() {
    when(page.locator(PASSWORD_SELECTOR).count()).thenReturn(0);
    authenticateExpecting(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    verify(username, never()).fill(anyString());
    assertFailureLog(
        BrowserAuthStage.LOGIN_FORM_VALIDATION, VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
  }

  @Test
  void submissionFailureHasItsOwnStageAndDoesNotExposeCredentials() {
    doThrow(new PlaywrightException(URL + " " + PASSWORD)).when(password).fill(anyString());
    authenticateExpecting(VulcanAuthFailureCategory.TRANSIENT);
    assertFailureLog(BrowserAuthStage.CREDENTIAL_SUBMISSION, VulcanAuthFailureCategory.TRANSIENT);
  }

  @Test
  void unsafePostLoginNavigationRemainsUnsupportedWithSanitizedStage() {
    doAnswer(
            invocation -> {
              when(page.url()).thenReturn("https://external.example/secret");
              return null;
            })
        .when(submitter)
        .click();
    authenticateExpecting(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    assertFailureLog(
        BrowserAuthStage.POST_LOGIN_VALIDATION, VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
  }

  @Test
  void missingSessionWithVisiblePasswordStillMeansInvalidCredentials() {
    when(page.locator(PASSWORD_SELECTOR).nth(0).isVisible()).thenReturn(true);
    authenticateExpecting(VulcanAuthFailureCategory.INVALID_CREDENTIALS);
    assertFailureLog(
        BrowserAuthStage.SESSION_CAPTURE, VulcanAuthFailureCategory.INVALID_CREDENTIALS);
  }

  @Test
  void rawPlaywrightFailureIsNotLoggedOrAttached() {
    when(page.navigate(anyString()))
        .thenThrow(new PlaywrightException(URL + " " + USERNAME + " " + PASSWORD));
    authenticateExpecting(VulcanAuthFailureCategory.TRANSIENT);
    assertFailureLog(BrowserAuthStage.INITIAL_NAVIGATION, VulcanAuthFailureCategory.TRANSIENT);
  }

  @Test
  void unexpectedRuntimeFailureRemainsProtocolFailureWithoutExceptionDetails() {
    when(page.navigate(anyString())).thenThrow(new IllegalStateException(URL + " " + PASSWORD));
    authenticateExpecting(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    assertFailureLog(
        BrowserAuthStage.INITIAL_NAVIGATION, VulcanAuthFailureCategory.PROTOCOL_FAILURE);
  }

  @ParameterizedTest
  @ValueSource(strings = {CAPTCHA_SELECTOR, MFA_SELECTOR})
  void hiddenChallengeMarkupIsIgnoredButALaterVisibleChallengeIsRejected(String selector) {
    Locator challenges = page.locator(selector);
    when(challenges.count()).thenReturn(2);
    PlaywrightVulcanBrowserAuthenticator.rejectInteractiveSecurity(page);
    when(challenges.nth(1).isVisible()).thenReturn(true);
    assertThatThrownBy(() -> PlaywrightVulcanBrowserAuthenticator.rejectInteractiveSecurity(page))
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            exception ->
                assertThat(exception.category())
                    .isEqualTo(
                        selector.equals(CAPTCHA_SELECTOR)
                            ? VulcanAuthFailureCategory.CAPTCHA_REQUIRED
                            : VulcanAuthFailureCategory.MFA_REQUIRED));
  }

  @ParameterizedTest
  @ValueSource(strings = {CAPTCHA_SELECTOR, MFA_SELECTOR})
  void visiblePostLoginChallengesRetainTheirCategoryAndStage(String selector) {
    doAnswer(
            invocation -> {
              when(page.locator(selector).count()).thenReturn(1);
              when(page.locator(selector).nth(0).isVisible()).thenReturn(true);
              return null;
            })
        .when(submitter)
        .click();
    VulcanAuthFailureCategory category =
        selector.equals(CAPTCHA_SELECTOR)
            ? VulcanAuthFailureCategory.CAPTCHA_REQUIRED
            : VulcanAuthFailureCategory.MFA_REQUIRED;
    authenticateExpecting(category);
    assertFailureLog(BrowserAuthStage.POST_LOGIN_VALIDATION, category);
  }

  @ParameterizedTest
  @ValueSource(strings = {CAPTCHA_SELECTOR, MFA_SELECTOR})
  void inertChallengeMarkupDoesNotRepresentAnInteractiveChallenge(String selector) {
    Locator challenges = page.locator(selector);
    when(challenges.count()).thenReturn(1);
    when(challenges.nth(0).isVisible()).thenReturn(true);
    when(challenges.nth(0).locator("xpath=ancestor-or-self::*[@inert]").count()).thenReturn(1);
    PlaywrightVulcanBrowserAuthenticator.rejectInteractiveSecurity(page);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://external.example/collect",
        "http://school.vulcan.net.pl/",
        "https://127.0.0.1/",
        "https://localhost/",
        "https://school.vulcan.net.pl:8443/",
        "https://user:password@school.vulcan.net.pl/"
      })
  void credentialGuardStillAbortsUnsafeRequests(String destination) {
    Consumer<Route> guard = captureGuard();
    Route route = mock(Route.class, RETURNS_DEEP_STUBS);
    when(route.request().url()).thenReturn(destination);
    guard.accept(route);
    verify(route).abort();
    verify(route, never()).resume();
  }

  @Test
  void credentialGuardAllowsOnlySafeVulcanDestinationWithQueryAndFragment() {
    Consumer<Route> guard = captureGuard();
    Route route = mock(Route.class, RETURNS_DEEP_STUBS);
    when(route.request().url()).thenReturn(URL + "#schedule");
    guard.accept(route);
    verify(route).resume();
    verify(route, never()).abort();
  }

  private Consumer<Route> captureGuard() {
    AtomicReference<Consumer<Route>> guard = new AtomicReference<>();
    doAnswer(
            invocation -> {
              guard.set(invocation.getArgument(1));
              return null;
            })
        .when(context)
        .route(eq("**/*"), any());
    authenticateExpecting(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    return guard.get();
  }

  private void authenticateExpecting(VulcanAuthFailureCategory category) {
    try (var staticPlaywright = mockStatic(Playwright.class);
        var request = new VulcanLoginRequest(URI.create(URL), USERNAME, PASSWORD.toCharArray())) {
      staticPlaywright.when(Playwright::create).thenReturn(playwright);
      assertThatThrownBy(() -> authenticator.authenticate(request))
          .isInstanceOfSatisfying(
              VulcanAuthenticationException.class,
              exception -> {
                assertThat(exception.category()).isEqualTo(category);
                assertThat(exception.getCause()).isNull();
                assertThat(exception.getMessage()).doesNotContain(URL, USERNAME, PASSWORD);
              });
    }
  }

  private void assertFailureLog(BrowserAuthStage stage, VulcanAuthFailureCategory category) {
    assertThat(logs.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getFormattedMessage())
                  .isEqualTo(
                      "VULCAN browser authentication failed: stage="
                          + stage
                          + " category="
                          + category);
              assertThat(event.getThrowableProxy()).isNull();
              assertThat(event.getArgumentArray()).containsExactly(stage, category);
            });
  }
}
