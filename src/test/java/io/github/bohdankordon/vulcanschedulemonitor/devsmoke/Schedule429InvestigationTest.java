package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Schedule429InvestigationTest {
  @Test
  void explicitSeparateOptInIsRequired() {
    assertThat(VulcanSchedule429Investigation.authorized(new String[0])).isFalse();
    assertThat(VulcanSchedule429Investigation.authorized(new String[] {"--authorized-local-smoke"}))
        .isFalse();
    assertThat(
            VulcanSchedule429Investigation.authorized(
                new String[] {"--authorized-schedule-429-investigation", "extra"}))
        .isFalse();
    assertThat(
            VulcanSchedule429Investigation.authorized(
                new String[] {"--authorized-schedule-429-investigation"}))
        .isTrue();
  }

  @Test
  void browserPermitCannotBeReusedEvenAfterFailureOrRearming() {
    var budget = new Schedule429Budget();
    assertThat(budget.permitBrowser(true)).isFalse();
    budget.arm();
    assertThat(budget.permitBrowser(false)).isFalse();
    assertThat(budget.permitBrowser(true)).isTrue();
    budget.browserResult(429, false);
    budget.arm();
    for (int i = 0; i < 5; i++) assertThat(budget.permitBrowser(true)).isFalse();
    assertThat(budget.browserRequests()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 204, 301, 401, 403, 429, 500})
  void noJavaComparisonWithoutSuccessfulBrowserJsonEnvelope(int status) {
    var budget = new Schedule429Budget();
    budget.arm();
    budget.permitBrowser(true);
    budget.browserResult(status, false);
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                VulcanSchedule429Investigation.compareJava(
                    new Schedule429Report(), budget, calls::incrementAndGet))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(0);
  }

  @ParameterizedTest
  @ValueSource(ints = {301, 401, 429, 503})
  void evenJsonCannotPermitJavaAfterNon2xx(int status) {
    var budget = new Schedule429Budget();
    budget.arm();
    budget.permitBrowser(true);
    budget.browserResult(status, true);
    assertThat(budget.javaPermitted()).isFalse();
  }

  @ParameterizedTest
  @CsvSource({
    "200,2,SUCCESS",
    "429,3,RATE_LIMITED",
    "403,4,AUTHENTICATION_REQUIRED",
    "500,4,SERVER_ERROR"
  })
  void comparisonUsesExactlyOneProductionCallAndReportsFiniteCase(
      int status, String matrix, String outcome) {
    var budget = new Schedule429Budget();
    budget.arm();
    budget.permitBrowser(true);
    budget.browserResult(200, true);
    var report = new Schedule429Report();
    var calls = new AtomicInteger();
    VulcanSchedule429Investigation.compareJava(
        report,
        budget,
        () -> {
          calls.incrementAndGet();
          if (status != 200) throw VulcanHttpException.responseFailure("synthetic", status);
        });
    assertThat(calls).hasValue(1);
    assertThatThrownBy(
            () ->
                VulcanSchedule429Investigation.compareJava(report, budget, calls::incrementAndGet))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(1);
    assertThat(render(report)).contains("decisionCase=" + matrix, "javaOutcome=" + outcome);
  }

  @ParameterizedTest
  @CsvSource({
    "https://school.vulcan.net.pl/private/unit/PlanLekcji.mvc/Index,PLAN_PAGE",
    "https://school.vulcan.net.pl/private/unit/PlanLekcji,PLAN_PAGE",
    "https://school.vulcan.net.pl/private/unit/Home.mvc/Index,HOME_OR_LANDING",
    "https://school.vulcan.net.pl/private/unit/,HOME_OR_LANDING",
    "https://school.vulcan.net.pl/private/unit/Dziennik.mvc, JOURNAL_PAGE",
    "https://school.vulcan.net.pl/private/unit/Other.mvc?next=PlanLekcji,OTHER_ALLOWED",
    "https://untrusted.example/private/PlanLekcji.mvc,UNAVAILABLE"
  })
  void refererUsesGenericRouteOnly(String uri, String expected) {
    assertThat(Schedule429Structure.referer(uri)).isEqualTo(expected);
  }

  @Test
  void headerAndCookieReportsNeverExposeNamesValuesOrTenant() {
    var report = new Schedule429Report();
    Schedule429Structure.headers(
        report,
        "browser",
        Map.of(
            "Cookie",
            "PrivateCookie=secret; Another=private-value",
            "USER-AGENT",
            "private-agent",
            "X-V-AppGuid",
            "private-guid",
            "Referer",
            "https://school.vulcan.net.pl/private/unit/PlanLekcji.mvc",
            "Sec-Fetch-Site",
            "same-origin"));
    assertThat(render(report))
        .contains(
            "browser.cookieCount=2",
            "browser.appGuidHeaderPresent=true",
            "browser.verificationHeaderPresent=false",
            "browser.fetchMetadataPresent=true",
            "browser.refererContext=PLAN_PAGE")
        .doesNotContain(
            "PrivateCookie",
            "Another",
            "private-value",
            "private-guid",
            "private-agent",
            "/private/");
    var base = URI.create("https://school.vulcan.net.pl/private/unit/");
    var before =
        new VulcanSessionMaterial(base, base, "secret-token", "secret-guid", "PrivateCookie=one");
    var rotated =
        new VulcanSessionMaterial(
            base,
            base.resolve("PlanLekcji.mvc"),
            "rotated-token",
            "secret-guid",
            "PrivateCookie=two");
    Schedule429Structure.verificationDrift(report, before, rotated);
    assertThat(render(report))
        .contains(
            "postLoginCookieCount=1",
            "verifiedCookieCount=1",
            "verificationChangedCookieMaterial=true",
            "verificationChangedCookieCount=false",
            "verificationChangedRefererContext=true")
        .doesNotContain("rotated-token", "PrivateCookie");
    var added =
        new VulcanSessionMaterial(
            base, base, "secret-token", "secret-guid", "PrivateCookie=two; NewCookie=three");
    Schedule429Structure.verificationDrift(report, before, added);
    assertThat(render(report))
        .contains("verifiedCookieCount=2", "verificationChangedCookieCount=true")
        .doesNotContain("NewCookie");
  }

  @Test
  void formComparesEncodingShapeWeekAndAnchorWithoutOutputtingValues() {
    var week = LocalDate.of(2026, 8, 31);
    var facts =
        Schedule429Structure.form(
            "POST",
            "dataOd=2026-08-31T00%3A00%3A00&dataDo=2026-09-06T00%3A00%3A00&idDziennik=98765&data=2026-09-05T00%3A00%3A00",
            "application/x-www-form-urlencoded;charset=UTF-8",
            week);
    assertThat(facts.fields()).isTrue();
    assertThat(facts.week()).isTrue();
    assertThat(facts.anchorAtStart()).isFalse();
    var report = new Schedule429Report();
    Schedule429Structure.formReport(report, "browser", facts);
    assertThat(render(report)).doesNotContain("98765", "2026-08", "2026-09");
    assertThat(Schedule429Structure.formValues("data=one&data=two")).isEmpty();
    assertThat(Schedule429Structure.formValues("data=%invalid")).isEmpty();
  }

  @Test
  void jsonValidationDiscardsBytesAndOnlyEmitsShape() {
    var report = new Schedule429Report();
    byte[] bytes =
        "{\"success\":true,\"data\":{\"planLekcji\":[{\"teacher\":\"private-person\"}],\"planLekcjiZeZmianami\":[]}}"
            .getBytes(StandardCharsets.UTF_8);
    assertThat(Schedule429Structure.jsonEnvelope(report, bytes)).isTrue();
    assertThat(bytes).containsOnly((byte) 0);
    assertThat(render(report))
        .contains("browser.scheduleArraysPresent=true")
        .doesNotContain("private-person", "teacher", "planLekcji");
  }

  @Test
  void strictSchemaRejectsArbitraryKeysAndEverySecretBearingValue() {
    for (String value :
        new String[] {
          "https://private.example/path",
          "secret\nresult=SUCCESS",
          "PLAN_PAGE private-token",
          "secret-cookie=value",
          "<html>body</html>"
        }) {
      for (String key : Schedule429Report.SCHEMA.keySet())
        assertThatThrownBy(() -> new Schedule429Report().put(key, value))
            .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new Schedule429Report().put("rawUrl", true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Schedule429Report().put("browserScheduleRequests", 2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void realProductionTransportIsMeasuredUsingSyntheticLoopbackOnly() throws Exception {
    var shape = Schedule429JavaShape.measure();
    var report = new Schedule429Report();
    Schedule429Structure.headers(report, "java", shape.headers());
    assertThat(render(report))
        .contains(
            "java.userAgentPresent=true",
            "java.acceptLanguagePresent=false",
            "java.fetchMetadataPresent=false",
            "java.verificationHeaderPresent=true",
            "java.appGuidHeaderPresent=true",
            "java.originPresent=true",
            "java.xRequestedWithPresent=true",
            "java.contentTypePresent=true",
            "java.cookieHeaderPresent=true");
    assertThat(shape.form())
        .isEqualTo(new Schedule429Structure.FormFacts(true, true, true, true, true, true, true));
  }

  @Test
  void diagnosticSourcesAreOutsideProductionAndHaveNoPersistenceOrHeaderSpoofing()
      throws Exception {
    String browser =
        Files.readString(
            Path.of(
                "src/test/java/io/github/bohdankordon/vulcanschedulemonitor/vulcan/connection/playwright/Schedule429Browser.java"));
    assertThat(browser)
        .contains(
            "ServiceWorkerPolicy.BLOCK",
            "budget.permitBrowser",
            "route.resume();",
            "new VulcanSessionCapture(portalUrls)",
            "LoginFormSubmissionPolicy(portalUrls)",
            "VulcanPrivacyConsent.dismissIfPresent",
            "rejectInteractiveSecurity(page)");
    assertThat(browser)
        .doesNotContain(
            "setExtraHTTPHeaders",
            "setUserAgent",
            "setHeaders(",
            "setPostData(",
            ".screenshot(",
            ".tracing(",
            ".storageState(",
            "setRecordHar",
            "newContext(\"",
            "addCookies(");
    String driver =
        Files.readString(
            Path.of(
                "src/test/java/io/github/bohdankordon/vulcanschedulemonitor/devsmoke/VulcanSchedule429Investigation.java"));
    assertThat(driver)
        .contains(
            "new VulcanClient(VulcanSession.fromMaterial(postLogin))",
            "DefaultVulcanSessionVerifier")
        .doesNotContain("SpringApplication.run", "sessions.replace", "secrets.", "System.getenv");
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(Schedule429Failure.Stage.class)
  void firstFailureStageSurvivesLaterFailuresWithoutExceptionData(Schedule429Failure.Stage stage) {
    var report = new Schedule429Report();
    report.stage(stage);
    report.fail(new IllegalStateException("https://private.example/secret cookie=value"));
    report.fail(
        Schedule429Failure.Stage.BROWSER_CLEANUP,
        new Schedule429Failure(Schedule429Failure.Category.NOT_FOUND));
    assertThat(render(report))
        .contains("stage=" + stage.name(), "failureCategory=INTERNAL_INVARIANT")
        .doesNotContain("private.example", "cookie", "secret", "failureCategory=NOT_FOUND");
    assertThatThrownBy(report::throwIfFailed)
        .isInstanceOf(Schedule429Failure.class)
        .hasMessage("INTERNAL_INVARIANT")
        .hasNoCause();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(Schedule429Failure.Category.class)
  void allFiniteFailureCategoriesAreAcceptedWithoutRawExceptions(
      Schedule429Failure.Category category) {
    var report = new Schedule429Report();
    report.stage(Schedule429Failure.Stage.BROWSER_CONTROL_WAIT);
    report.fail(new Schedule429Failure(category));
    assertThat(render(report)).contains("failureCategory=" + category.name());
  }

  @Test
  void timeoutAndPlaywrightFailuresHaveNarrowSafeCategories() {
    var report = new Schedule429Report();
    report.stage(Schedule429Failure.Stage.BROWSER_CONTROL_WAIT);
    report.fail(new com.microsoft.playwright.TimeoutError("private selector"));
    assertThat(render(report))
        .contains("failureCategory=REQUEST_NOT_OBSERVED")
        .doesNotContain("selector");
    report = new Schedule429Report();
    report.stage(Schedule429Failure.Stage.AUTHENTICATED_BROWSER_READY);
    report.fail(new com.microsoft.playwright.TimeoutError("private URL"));
    assertThat(render(report))
        .contains("failureCategory=NAVIGATION_TIMEOUT")
        .doesNotContain("private");
    report = new Schedule429Report();
    report.fail(new com.microsoft.playwright.PlaywrightException("private value"));
    assertThat(render(report))
        .contains("failureCategory=PLAYWRIGHT_TRANSIENT")
        .doesNotContain("private");
  }

  @Test
  void powershellAllowsExactlyTheSameFiniteFailureTaxonomy() throws Exception {
    String script = Files.readString(Path.of("scripts/vulcan-real-smoke.ps1"));
    for (String key : new String[] {"stage", "failureCategory"})
      assertThat(script).contains(key + " = '" + Schedule429Report.SCHEMA.get(key) + "'");
  }

  @Test
  void javaProductionRequestUsesImmutablePreRequestMaterialExactlyOnce() throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/synthetic/");
    final var postLogin =
        new VulcanSessionMaterial(base, base, "original-token", "original-guid", "Original=before");
    var verified =
        new VulcanSessionMaterial(
            base, base.resolve("Other.mvc"), "verified-token", "verified-guid", "Rotated=after");
    var calls = new AtomicInteger();
    var originalUsed = new java.util.concurrent.atomic.AtomicBoolean();
    server.createContext(
        "/synthetic/PlanLekcji.mvc/GetPlanLekcjiContext",
        exchange -> {
          try (exchange) {
            calls.incrementAndGet();
            var headers = exchange.getRequestHeaders();
            originalUsed.set(
                postLogin.cookieHeader().equals(headers.getFirst("Cookie"))
                    && postLogin
                        .requestVerificationToken()
                        .equals(headers.getFirst("X-V-RequestVerificationToken"))
                    && postLogin.appGuid().equals(headers.getFirst("X-V-AppGuid"))
                    && postLogin.refererUri().toString().equals(headers.getFirst("Referer")));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Set-Cookie", "Original=after-request; Path=/");
            byte[] body =
                "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try {
      var budget = new Schedule429Budget();
      budget.arm();
      budget.permitBrowser(true);
      budget.browserResult(200, true);
      var report = new Schedule429Report();
      Schedule429Structure.verificationDrift(report, postLogin, verified);
      VulcanSchedule429Investigation.compareJavaFromPostLogin(
          report, budget, postLogin, 1, LocalDate.of(2026, 8, 31));
      assertThat(originalUsed).isTrue();
      assertThat(calls).hasValue(1);
      assertThat(postLogin.cookieHeader()).isEqualTo("Original=before");
      assertThatThrownBy(
              () ->
                  VulcanSchedule429Investigation.compareJavaFromPostLogin(
                      report, budget, postLogin, 1, LocalDate.of(2026, 8, 31)))
          .isInstanceOf(IllegalStateException.class);
      assertThat(calls).hasValue(1);
      assertThat(render(report))
          .contains("javaMaterialContext=PRE_REQUEST_POST_LOGIN", "decisionCase=2")
          .doesNotContain("original-token", "Original", "Rotated", "after-request");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void controlHasNoNavigationAndOnlyKnownAjaxHeadersAreExplicit() throws Exception {
    String browser =
        Files.readString(
            Path.of(
                "src/test/java/io/github/bohdankordon/vulcanschedulemonitor/vulcan/connection/playwright/Schedule429Browser.java"));
    String control =
        browser.substring(
            browser.indexOf("  public void control("),
            browser.indexOf("  public int scheduleStatus()"));
    assertThat(control)
        .doesNotContain(
            ".navigate(",
            ".click(",
            "getByText",
            "postPlan",
            "jQuery",
            "'Cookie'",
            "'User-Agent'",
            "'Origin'",
            "'Referer'",
            "'Accept-Language'",
            "'Sec-Fetch-")
        .contains(
            "X-V-RequestVerificationToken",
            "X-V-AppGuid",
            "X-Requested-With",
            "XMLHttpRequest",
            "credentials: 'same-origin'",
            "redirect: 'manual'",
            "new URLSearchParams(form)");
    String driver =
        Files.readString(
            Path.of(
                "src/test/java/io/github/bohdankordon/vulcanschedulemonitor/devsmoke/VulcanSchedule429Investigation.java"));
    assertThat(driver)
        .contains(
            "final var postLogin = browser.authenticate(request)",
            "browser.control(postLogin, verified.classes(), week)",
            "compareJavaFromPostLogin(report, budget, postLogin, browser.journal(), week)");
    assertThat(driver.indexOf("final var postLogin"))
        .isLessThan(driver.indexOf("verifyAndDiscover(postLogin)"));
    assertThat(driver)
        .doesNotContain("fromMaterial(verified", "fromMaterial(postPlan", "postPlanMaterial");
  }

  @Test
  void unchangedAndReorderedCookiesDoNotImplyVerificationDrift() {
    var base = URI.create("https://school.vulcan.net.pl/private/unit/");
    var postLogin =
        new VulcanSessionMaterial(
            base, base, "secret-token", "secret-guid", "First=one; Second=two");
    var verified =
        new VulcanSessionMaterial(
            base, base, "secret-token", "secret-guid", "Second=two; First=one");
    var report = new Schedule429Report();
    Schedule429Structure.verificationDrift(report, postLogin, verified);
    assertThat(render(report))
        .contains(
            "verificationChangedCookieCount=false",
            "verificationChangedCookieMaterial=false",
            "verificationChangedRefererContext=false")
        .doesNotContain("First", "Second", "secret-token", "one", "two");
  }

  static String render(Schedule429Report report) {
    var bytes = new ByteArrayOutputStream();
    report.print(new PrintStream(bytes));
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
