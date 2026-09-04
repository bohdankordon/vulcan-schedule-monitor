package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
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
      page.onRequest(
          observed -> {
            try {
              observations.add(
                  new BrowserRequestObservation(URI.create(observed.url()), observed.headers()));
            } catch (RuntimeException ignored) {
              // Malformed or unavailable metadata is never used for session capture.
            }
          });
      page.navigate(request.portalUri().toASCIIString());
      requireAllowedPage(page);
      locateDirectLogin(page);
      requireAllowedPage(page);
      rejectInteractiveSecurity(page);

      Locator username =
          page.locator("input[autocomplete='username'], input[name='LoginName']").first();
      Locator password =
          page.locator("input[autocomplete='current-password'], input[type='password']").first();
      if (username.count() == 0 || password.count() == 0) {
        throw new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
      }
      char[] passwordChars = request.password();
      try {
        username.fill(request.login());
        requireAllowedPage(page);
        password.fill(new String(passwordChars));
      } finally {
        Arrays.fill(passwordChars, '\0');
      }
      page.locator("input[type='submit'], button[type='submit']").first().click();
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
      if (observation.header("x-v-requestverificationtoken") == null
          || observation.header("x-v-appguid") == null) {
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
}
