package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanBrowserAuthenticator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanLoginRequest;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class PlaywrightVulcanBrowserAuthenticator implements VulcanBrowserAuthenticator {

  private final PortalUrlValidator portalUrls;
  private final boolean headless;

  public PlaywrightVulcanBrowserAuthenticator(PortalUrlValidator portalUrls, boolean headless) {
    this.portalUrls = portalUrls;
    this.headless = headless;
  }

  @Override
  public VulcanSessionMaterial authenticate(VulcanLoginRequest request) {
    List<BrowserRequestObservation> observations = new CopyOnWriteArrayList<>();
    try (Playwright playwright = Playwright.create();
        Browser browser =
            playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
        BrowserContext context = browser.newContext();
        Page page = context.newPage()) {
      page.onRequest(observed -> observeAuthenticatedRequest(observed, observations));
      page.navigate(request.portalUri().toASCIIString());
      requireAllowedPage(page);
      locateDirectLogin(page);
      requireAllowedPage(page);
      rejectInteractiveSecurity(page);

      VerifiedLoginForm loginForm = requireSafeLoginForm(page);
      context.route("**/*", this::guardCredentialRequest);
      char[] passwordChars = request.password();
      try {
        loginForm.username().fill(request.login());
        requireAllowedPage(page);
        loginForm.password().fill(new String(passwordChars));
        requireSafeSubmission(page, loginForm.form(), loginForm.submitter());
        loginForm.submitter().click();
      } finally {
        Arrays.fill(passwordChars, '\0');
      }
      page.waitForLoadState(LoadState.DOMCONTENTLOADED);
      page.waitForTimeout(2_000);
      requireAllowedPage(page);
      rejectInteractiveSecurity(page);

      VulcanSessionCapture capture = new VulcanSessionCapture(portalUrls);
      try {
        return capture.capture(observations, cookiesForObservedApplication(context, observations));
      } catch (VulcanAuthenticationException exception) {
        if (isVisible(
            page.locator("input[autocomplete='current-password'], input[type='password']"))) {
          throw new VulcanAuthenticationException(VulcanAuthFailureCategory.INVALID_CREDENTIALS);
        }
        throw exception;
      }
    } catch (VulcanAuthenticationException exception) {
      throw exception;
    } catch (PlaywrightException exception) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.TRANSIENT);
    } catch (RuntimeException exception) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    }
  }

  private VerifiedLoginForm requireSafeLoginForm(Page page) {
    Locator passwords =
        page.locator("input[autocomplete='current-password'], input[type='password']");
    if (passwords.count() != 1) {
      throw unsupported();
    }
    Locator password = passwords.first();
    Locator forms = password.locator("xpath=ancestor::form[1]");
    if (forms.count() != 1) {
      throw unsupported();
    }
    Locator form = forms.first();
    Locator usernames = form.locator("input[autocomplete='username'], input[name='LoginName']");
    Locator submitters =
        form.locator(
            "input[type='submit'], button[type='submit'], button:not([type]), input[type='image']");
    if (usernames.count() != 1 || submitters.count() == 0) {
      throw unsupported();
    }
    Locator submitter = submitters.first();
    requireSafeSubmission(page, form, submitter);
    return new VerifiedLoginForm(form, usernames.first(), password, submitter);
  }

  private void requireSafeSubmission(Page page, Locator form, Locator submitter) {
    try {
      boolean actionOverride = submitter.getAttribute("formaction") != null;
      boolean methodOverride = submitter.getAttribute("formmethod") != null;
      new LoginFormSubmissionPolicy(portalUrls)
          .requireSafeSubmission(
              URI.create(page.url()),
              true,
              true,
              stringProperty(form, "form => form.action"),
              stringProperty(form, "form => form.method"),
              actionOverride,
              actionOverride
                  ? stringProperty(submitter, "submitter => submitter.formAction")
                  : null,
              methodOverride,
              methodOverride
                  ? stringProperty(submitter, "submitter => submitter.formMethod")
                  : null);
    } catch (VulcanAuthenticationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unsupported();
    }
  }

  private void observeAuthenticatedRequest(
      Request observed, List<BrowserRequestObservation> observations) {
    try {
      URI uri = URI.create(observed.url());
      if (!portalUrls.isAllowed(uri)) {
        return;
      }
      BrowserRequestObservation observation =
          new BrowserRequestObservation(
              uri,
              observed.headerValue("referer"),
              observed.headerValue("x-v-requestverificationtoken"),
              observed.headerValue("x-v-appguid"));
      if (observation.isComplete()) {
        observations.add(observation);
      }
    } catch (RuntimeException ignored) {
      // Malformed or incomplete metadata is discarded immediately.
    }
  }

  private void guardCredentialRequest(Route route) {
    URI destination;
    try {
      destination = URI.create(route.request().url());
    } catch (RuntimeException exception) {
      route.abort();
      return;
    }
    if (portalUrls.isAllowed(destination)) {
      route.resume();
    } else {
      route.abort();
    }
  }

  private static String stringProperty(Locator locator, String expression) {
    Object value = locator.evaluate(expression);
    if (!(value instanceof String text)) {
      throw unsupported();
    }
    return text;
  }

  private static VulcanAuthenticationException unsupported() {
    return new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
  }

  private void locateDirectLogin(Page page) {
    if (page.locator("input[autocomplete='username'], input[name='LoginName']").count() > 0) {
      return;
    }
    Locator direct =
        page.locator("a[title*='nauczyciel'], a[title*='pracownik'], a[href*='LoginEndpoint.aspx']")
            .first();
    if (direct.count() == 0) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    }
    direct.click();
    page.waitForLoadState(LoadState.DOMCONTENTLOADED);
  }

  private void requireAllowedPage(Page page) {
    URI current;
    try {
      current = URI.create(page.url());
    } catch (IllegalArgumentException exception) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    }
    if (!portalUrls.isAllowed(current)) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    }
  }

  private static void rejectInteractiveSecurity(Page page) {
    if (isVisible(page.locator("iframe[src*='captcha'], [class*='captcha'], [id*='captcha']"))) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.CAPTCHA_REQUIRED);
    }
    if (isVisible(page.locator("input[autocomplete='one-time-code']"))) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.MFA_REQUIRED);
    }
  }

  private static boolean isVisible(Locator locator) {
    return locator.count() > 0 && locator.first().isVisible();
  }

  private static List<BrowserCookieObservation> cookiesForObservedApplication(
      BrowserContext context, List<BrowserRequestObservation> observations) {
    for (int index = observations.size() - 1; index >= 0; index--) {
      BrowserRequestObservation observation = observations.get(index);
      if (!observation.isComplete()) {
        continue;
      }
      URI origin = toOrigin(observation.uri());
      List<Cookie> cookies = context.cookies(observation.uri().toASCIIString());
      return cookies.stream()
          .map(cookie -> new BrowserCookieObservation(origin, cookie.name, cookie.value))
          .toList();
    }
    return List.of();
  }

  private static URI toOrigin(URI uri) {
    try {
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/", null, null);
    } catch (URISyntaxException exception) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    }
  }

  private record VerifiedLoginForm(
      Locator form, Locator username, Locator password, Locator submitter) {}
}
