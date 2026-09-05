package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Failure.Category.*;
import static io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Failure.Stage.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Budget;
import io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Failure;
import io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Report;
import io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Structure;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanLoginRequest;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.Stage;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Schedule429Browser implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(Schedule429Browser.class);
  private final PortalUrlValidator portalUrls;
  private final boolean headless;
  private final VulcanDiagnostics diagnostics;

  private final Schedule429Budget budget;
  private final Schedule429Report report;
  private final Runnable unexpectedScheduleTraffic;
  private Playwright playwright;
  private Browser browser;
  private BrowserContext context;
  private Page page;
  private CDPSession redirectGuard;
  private final List<BrowserRequestObservation> observations = new CopyOnWriteArrayList<>();
  private final Set<Long> allowedJournals = new HashSet<>();
  private LocalDate week;
  private Long selectedJournal;
  private URI application;
  private Request scheduleRequest;
  private int scheduleStatus;
  private URI capturedReferer;
  private boolean trafficStopped;
  private boolean fallback;
  private Schedule429Structure.FormFacts browserForm;

  public Schedule429Browser(
      VulcanDiagnostics diagnostics, Schedule429Report report, Schedule429Budget budget) {
    this(diagnostics, report, budget, null);
  }

  public static Schedule429Browser authenticationOnly(
      VulcanDiagnostics diagnostics, Schedule429Report report, Runnable unexpectedScheduleTraffic) {
    return new Schedule429Browser(
        diagnostics,
        report,
        new Schedule429Budget(),
        java.util.Objects.requireNonNull(unexpectedScheduleTraffic));
  }

  private Schedule429Browser(
      VulcanDiagnostics diagnostics,
      Schedule429Report report,
      Schedule429Budget budget,
      Runnable unexpectedScheduleTraffic) {
    this.unexpectedScheduleTraffic = unexpectedScheduleTraffic;
    this.portalUrls = new PortalUrlValidator();
    this.headless = true;
    this.diagnostics = diagnostics;
    this.report = report;
    this.budget = budget;
  }

  public VulcanSessionMaterial authenticate(VulcanLoginRequest request) {
    diagnostics.begin(Stage.BROWSER_AUTH);
    BrowserAuthStage stage = BrowserAuthStage.INITIAL_NAVIGATION;
    try {
      playwright = Playwright.create();
      browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(headless));
      context =
          browser.newContext(
              new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
      context.setDefaultTimeout(15_000);
      report.stage(BROWSER_REQUEST_OBSERVER_SETUP);
      context.route("**/*", this::guardCredentialRequest);
      context.onResponse(this::observeResponse);
      page = context.newPage();
      installRedirectGuard();
      page.onRequest(observed -> observeAuthenticatedRequest(observed, observations));
      report.stage(AUTHENTICATED_BROWSER_READY);
      page.navigate(request.portalUri().toASCIIString());
      requireAllowedPage(page);
      stage = BrowserAuthStage.COOKIE_CONSENT;
      VulcanPrivacyConsent.dismissIfPresent(page, portalUrls);
      stage = BrowserAuthStage.DIRECT_LOGIN_DISCOVERY;
      Locator directLogin = locateDirectLogin(page);
      if (directLogin != null) {
        stage = BrowserAuthStage.DIRECT_LOGIN_NAVIGATION;
        directLogin.click(new Locator.ClickOptions().setTimeout(30_000));
        page.waitForLoadState(
            LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(30_000));
      }
      requireAllowedPage(page);
      stage = BrowserAuthStage.COOKIE_CONSENT;
      VulcanPrivacyConsent.dismissIfPresent(page, portalUrls);
      stage = BrowserAuthStage.LOGIN_FORM_VALIDATION;
      rejectInteractiveSecurity(page);

      VerifiedLoginForm loginForm = requireSafeLoginForm(page);
      stage = BrowserAuthStage.CREDENTIAL_SUBMISSION;
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
      stage = BrowserAuthStage.POST_LOGIN_VALIDATION;
      page.waitForLoadState(LoadState.DOMCONTENTLOADED);
      page.waitForTimeout(2_000);
      requireAllowedPage(page);
      rejectInteractiveSecurity(page);

      stage = BrowserAuthStage.SESSION_CAPTURE;
      diagnostics.pass(Stage.BROWSER_AUTH);
      diagnostics.begin(Stage.SESSION_CAPTURE);
      VulcanSessionCapture capture = new VulcanSessionCapture(portalUrls);
      try {
        VulcanSessionMaterial material =
            capture.capture(observations, cookiesForObservedApplication(context, observations));
        diagnostics.pass(Stage.SESSION_CAPTURE);
        return material;
      } catch (VulcanAuthenticationException exception) {
        if (isVisible(
            page.locator("input[autocomplete='current-password'], input[type='password']"))) {
          throw new VulcanAuthenticationException(VulcanAuthFailureCategory.INVALID_CREDENTIALS);
        }
        throw exception;
      }
    } catch (VulcanAuthenticationException exception) {
      report.fail(exception);
      logFailure(stage, exception.category());
      throw exception;
    } catch (PlaywrightException exception) {
      report.fail(exception);
      logFailure(stage, VulcanAuthFailureCategory.TRANSIENT);
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.TRANSIENT);
    } catch (RuntimeException exception) {
      report.fail(exception);
      logFailure(stage, VulcanAuthFailureCategory.PROTOCOL_FAILURE);
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    }
  }

  private static void logFailure(BrowserAuthStage stage, VulcanAuthFailureCategory category) {
    logger.warn("VULCAN browser authentication failed: stage={} category={}", stage, category);
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
      if (!portalUrls.isAllowedRuntimeUri(uri)) {
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
      if (application != null) stopUnsafeControl();
      route.abort();
      return;
    }
    if (unexpectedScheduleTraffic != null
        && destination.getPath().toLowerCase(Locale.ROOT).endsWith("/getplanlekcjicontext")) {
      unexpectedScheduleTraffic.run();
      trafficStopped = true;
      route.abort();
      throw new Schedule429Failure(SECURITY_INVARIANT);
    }
    if (!portalUrls.isAllowedRuntimeUri(destination)
        || trafficStopped
        || application != null
            && (!application.getScheme().equals(destination.getScheme())
                || !application.getRawAuthority().equals(destination.getRawAuthority()))) {
      if (unexpectedScheduleTraffic != null && !trafficStopped) {
        trafficStopped = true;
        route.abort();
        throw new Schedule429Failure(SECURITY_INVARIANT);
      }
      if (application != null && !trafficStopped) stopUnsafeControl();
      route.abort();
      return;
    }
    if (destination.getPath().toLowerCase(Locale.ROOT).endsWith("/getplanlekcjicontext")) {
      Request request = route.request();
      var values = Schedule429Structure.formValues(request.postData());
      Long candidate = safeTarget(destination, request.method(), values);
      if (candidate == null && application != null) stopUnsafeControl();
      if (!budget.permitBrowser(candidate != null)) {
        route.abort();
        return;
      }
      selectedJournal = candidate;
      scheduleRequest = request;
      report.put("browserSource", fallback ? "BROWSER_CONTEXT_FETCH" : "NATIVE_UI_REQUEST");
      report.put("browser.method", request.method().equals("POST") ? "POST" : "OTHER");
      Schedule429Structure.headers(report, "browser", request.allHeaders());
      report.put(
          "browserRefererMatchesCapturedReferer",
          capturedReferer != null
              && capturedReferer.toASCIIString().equals(request.headerValue("referer")));
      browserForm =
          Schedule429Structure.form(
              request.method(), request.postData(), request.headerValue("content-type"), week);
      Schedule429Structure.formReport(report, "browser", browserForm);
    }
    route.resume();
  }

  private void stopUnsafeControl() {
    trafficStopped = true;
    report.fail(BROWSER_CONTROL_TRIGGER, new Schedule429Failure(SECURITY_INVARIANT));
  }

  private Long safeTarget(URI destination, String method, Map<String, String> values) {
    try {
      if (application == null
          || !destination.equals(application.resolve("PlanLekcji.mvc/GetPlanLekcjiContext"))
          || !method.equals("POST")
          || !values.keySet().equals(Set.of("dataOd", "dataDo", "data", "idDziennik"))) return null;
      long journal = Long.parseLong(values.get("idDziennik"));
      if (!allowedJournals.contains(journal)
          || selectedJournal != null && selectedJournal != journal) return null;
      if (!values.get("dataOd").equals(Schedule429Structure.stamp(week))
          || !values.get("dataDo").equals(Schedule429Structure.stamp(week.plusDays(6))))
        return null;
      LocalDate from = LocalDate.parse(values.get("dataOd").substring(0, 10));
      LocalDate to = LocalDate.parse(values.get("dataDo").substring(0, 10));
      LocalDate anchor = LocalDate.parse(values.get("data").substring(0, 10));
      return from.equals(week)
              && to.equals(week.plusDays(6))
              && !anchor.isBefore(week)
              && !anchor.isAfter(to)
          ? journal
          : null;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private void observeResponse(com.microsoft.playwright.Response response) {
    try {
      if (scheduleRequest == null || !response.request().equals(scheduleRequest)) return;
      scheduleStatus = response.status();
      trafficStopped = true;
      report.put("browser.statusFamily", Schedule429Structure.statusFamily(scheduleStatus));
      report.put("browser.status429", scheduleStatus == 429);
      String content = Schedule429Structure.contentFamily(response.headerValue("content-type"));
      report.put("browser.contentFamily", content);
      boolean envelope = false;
      if (scheduleStatus >= 200 && scheduleStatus < 300 && content.equals("json")) {
        envelope = Schedule429Structure.jsonEnvelope(report, response.body());
      }
      budget.browserResult(scheduleStatus, envelope);
    } catch (RuntimeException failure) {
      report.fail(BROWSER_CONTROL_WAIT, failure);
      trafficStopped = true;
      budget.browserResult(scheduleStatus, false);
    }
  }

  // Browser redirects are automatic and are not reliably re-routed by Playwright. Pause only
  // schedule responses so a redirect cannot silently consume another schedule request.
  private void installRedirectGuard() {
    redirectGuard = context.newCDPSession(page);
    redirectGuard.on("Fetch.requestPaused", this::guardScheduleRedirect);
    var pattern = new JsonObject();
    pattern.addProperty("urlPattern", "*GetPlanLekcjiContext*");
    pattern.addProperty("requestStage", "Response");
    var patterns = new JsonArray();
    patterns.add(pattern);
    var parameters = new JsonObject();
    parameters.add("patterns", patterns);
    redirectGuard.send("Fetch.enable", parameters);
  }

  private void guardScheduleRedirect(JsonObject event) {
    var parameters = new JsonObject();
    parameters.add("requestId", event.get("requestId"));
    int status = event.has("responseStatusCode") ? event.get("responseStatusCode").getAsInt() : 0;
    if (status >= 300 && status < 400) {
      scheduleStatus = status;
      trafficStopped = true;
      report.put("browser.statusFamily", "3xx");
      report.put("browser.status429", false);
      report.put("browser.contentFamily", "UNAVAILABLE");
      budget.browserResult(status, false);
      parameters.addProperty("errorReason", "Aborted");
      redirectGuard.send("Fetch.failRequest", parameters);
    } else {
      // No response/header/body modification; Chromium continues its own request.
      redirectGuard.send("Fetch.continueRequest", parameters);
    }
  }

  /** Pump outstanding authentication callbacks before the authentication-only context is closed. */
  public void finishAuthenticationOnly() {
    if (unexpectedScheduleTraffic == null) throw new Schedule429Failure(INTERNAL_INVARIANT);
    page.waitForTimeout(100);
    requireAllowedPage(page);
    rejectInteractiveSecurity(page);
    report.throwIfFailed();
  }

  /** Compare from the existing authenticated Page; no navigation or assumed portal UI. */
  public void control(
      VulcanSessionMaterial postLogin, List<SchoolClass> classes, LocalDate currentWeek) {
    try {
      report.stage(TARGET_SELECTION);
      application = postLogin.applicationBaseUri();
      capturedReferer = postLogin.refererUri();
      week = java.util.Objects.requireNonNull(currentWeek);
      classes.forEach(item -> allowedJournals.add(item.journalId()));
      if (allowedJournals.isEmpty()) throw new Schedule429Failure(NOT_FOUND);
      selectedJournal = classes.getFirst().journalId();
      report.stage(BROWSER_CONTROL_SETUP);
      requireAllowedPage(page);
      rejectInteractiveSecurity(page);
      URI currentPage = URI.create(page.url());
      URI endpoint = scheduleEndpoint(application, currentPage);
      if (!portalUrls.isAllowedRuntimeUri(endpoint)
          || isVisible(
              page.locator("input[autocomplete='current-password'], input[type='password']")))
        throw new Schedule429Failure(UNEXPECTED_PAGE_STATE);
      report.put("browserPageContext", Schedule429Structure.referer(currentPage.toASCIIString()));
      report.stage(BROWSER_CONTROL_TRIGGER);
      fallback = true;
      budget.arm();
      // Only the three known AJAX headers are explicit. Chromium supplies its own browser headers.
      // Playwright receives ephemeral arguments; no JSON dump, CLI, environment, or storage is
      // used.
      page.evaluate(
          """
          ({endpoint, form, verification, appGuid, origin, applicationPath}) => {
            const target = new URL(endpoint, location.href);
            const expectedOrigin = new URL(origin).origin;
            if (location.origin !== expectedOrigin || target.origin !== expectedOrigin
                || !location.pathname.startsWith(applicationPath)) throw new Error('Unsafe context');
            void fetch(endpoint, {method: 'POST', mode: 'same-origin', credentials: 'same-origin',
              redirect: 'manual', body: new URLSearchParams(form), headers: {
                'X-V-RequestVerificationToken': verification,
                'X-V-AppGuid': appGuid,
                'X-Requested-With': 'XMLHttpRequest'
              }}).catch(() => {});
          }
          """,
          Map.of(
              "endpoint",
              endpoint.getRawPath(),
              "origin",
              application.getScheme() + "://" + application.getRawAuthority(),
              "applicationPath",
              application.getRawPath(),
              "verification",
              postLogin.requestVerificationToken(),
              "appGuid",
              postLogin.appGuid(),
              "form",
              Map.of(
                  "dataOd",
                  Schedule429Structure.stamp(week),
                  "dataDo",
                  Schedule429Structure.stamp(week.plusDays(6)),
                  "data",
                  Schedule429Structure.stamp(week),
                  "idDziennik",
                  Long.toString(selectedJournal))));
      report.stage(BROWSER_CONTROL_WAIT);
      page.waitForCondition(
          () -> {
            report.throwIfFailed();
            return scheduleStatus != 0;
          },
          new Page.WaitForConditionOptions().setTimeout(25000));
      report.throwIfFailed();
    } catch (RuntimeException failure) {
      report.fail(failure);
      throw failure;
    }
  }

  static URI scheduleEndpoint(URI application, URI current) {
    URI endpoint = application.resolve("PlanLekcji.mvc/GetPlanLekcjiContext");
    if (!java.util.Objects.equals(current.getScheme(), endpoint.getScheme())
        || !java.util.Objects.equals(current.getRawAuthority(), endpoint.getRawAuthority())
        || !current.getPath().startsWith(application.getPath())
        || !application.getPath().endsWith("/")
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null) throw new Schedule429Failure(UNEXPECTED_PAGE_STATE);
    return endpoint;
  }

  public int scheduleStatus() {
    return scheduleStatus;
  }

  public long journal() {
    return java.util.Objects.requireNonNull(selectedJournal);
  }

  public Schedule429Structure.FormFacts browserForm() {
    return browserForm;
  }

  @Override
  public void close() {
    trafficStopped = true;
    boolean failed = false;
    try {
      if (context != null) context.close();
    } catch (RuntimeException failure) {
      failed = true;
      report.fail(BROWSER_CLEANUP, failure);
    }
    try {
      if (browser != null) browser.close();
    } catch (RuntimeException failure) {
      failed = true;
      report.fail(BROWSER_CLEANUP, failure);
    }
    try {
      if (playwright != null) playwright.close();
    } catch (RuntimeException failure) {
      failed = true;
      report.fail(BROWSER_CLEANUP, failure);
    }
    if (failed) report.throwIfFailed();
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

  private Locator locateDirectLogin(Page page) {
    if (page.locator("input[autocomplete='username'], input[name='LoginName']").count() > 0) {
      return null;
    }
    Locator direct =
        page.locator("a[title*='nauczyciel'], a[title*='pracownik'], a[href*='LoginEndpoint.aspx']")
            .first();
    if (direct.count() == 0) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    }
    return direct;
  }

  private void requireAllowedPage(Page page) {
    URI current;
    try {
      current = URI.create(page.url());
    } catch (IllegalArgumentException exception) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    }
    if (!portalUrls.isAllowedRuntimeUri(current)) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
    }
  }

  static void rejectInteractiveSecurity(Page page) {
    if (isVisible(page.locator("iframe[src*='captcha'], [class*='captcha'], [id*='captcha']"))) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.CAPTCHA_REQUIRED);
    }
    if (isVisible(page.locator("input[autocomplete='one-time-code']"))) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.MFA_REQUIRED);
    }
  }

  private static boolean isVisible(Locator locator) {
    for (int index = 0; index < locator.count(); index++) {
      Locator candidate = locator.nth(index);
      if (candidate.isVisible()
          && candidate.locator("xpath=ancestor-or-self::*[@inert]").count() == 0) {
        return true;
      }
    }
    return false;
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
