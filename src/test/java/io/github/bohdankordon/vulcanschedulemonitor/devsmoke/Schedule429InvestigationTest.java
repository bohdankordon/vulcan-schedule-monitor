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
            "browser.secFetchHeadersPresent=true",
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
    Schedule429Structure.cookies(report, before, rotated);
    assertThat(render(report))
        .contains(
            "postLoginCookieCount=1",
            "postPlanCookieCount=1",
            "cookieSetChanged=true",
            "cookieNameSetChanged=false",
            "verificationTokenChanged=true",
            "appGuidChanged=false")
        .doesNotContain("rotated-token", "PrivateCookie");
    var added =
        new VulcanSessionMaterial(
            base, base, "secret-token", "secret-guid", "PrivateCookie=two; NewCookie=three");
    Schedule429Structure.cookies(report, before, added);
    assertThat(render(report))
        .contains("postPlanCookieCount=2", "cookieNameSetChanged=true")
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
            "java.secFetchHeadersPresent=false",
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
            "new VulcanClient(VulcanSession.fromMaterial(postPlan))",
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
    report.stage(Schedule429Failure.Stage.PLAN_CONTEXT_NAVIGATION);
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

  static String render(Schedule429Report report) {
    var bytes = new ByteArrayOutputStream();
    report.print(new PrintStream(bytes));
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
