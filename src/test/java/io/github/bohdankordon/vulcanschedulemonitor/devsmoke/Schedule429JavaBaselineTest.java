package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class Schedule429JavaBaselineTest {
  @Test
  void explicitIndependentOptIn() {
    assertThat(VulcanSchedule429JavaBaseline.authorized(new String[0])).isFalse();
    assertThat(VulcanSchedule429JavaBaseline.authorized(new String[] {"--authorized-local-smoke"}))
        .isFalse();
    assertThat(
            VulcanSchedule429JavaBaseline.authorized(
                new String[] {"--authorized-schedule-429-investigation"}))
        .isFalse();
    assertThat(
            VulcanSchedule429JavaBaseline.authorized(
                new String[] {"--authorized-schedule-429-java-baseline", "extra"}))
        .isFalse();
    assertThat(
            VulcanSchedule429JavaBaseline.authorized(
                new String[] {"--authorized-schedule-429-java-baseline"}))
        .isTrue();
  }

  @Test
  void unexpectedBrowserObservationIrrevocablyBlocksJava() {
    var budget = new Schedule429JavaBaselineBudget();
    budget.unexpectedBrowserSchedule();
    var count = new AtomicInteger();
    for (int i = 0; i < 3; i++)
      assertThatThrownBy(
              () ->
                  VulcanSchedule429JavaBaseline.runJava(
                      new Schedule429JavaBaselineReport(), budget, count::incrementAndGet))
          .isInstanceOf(Schedule429Failure.class);
    assertThat(count).hasValue(0);
    assertThat(budget.javaRequests()).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "200,J2,SUCCESS",
    "429,J1,RATE_LIMITED",
    "401,J3,AUTHENTICATION_REQUIRED",
    "403,J3,AUTHENTICATION_REQUIRED",
    "302,J3,SESSION_REDIRECT",
    "500,J4,SERVER_ERROR",
    "404,J4,PERMANENT_HTTP",
    "0,J4,TRANSPORT_ERROR",
    "-1,J3,UNEXPECTED_HTML",
    "-2,J4,PROTOCOL_FAILURE"
  })
  void oneAttemptClassifiesEveryOutcomeWithoutPrintingException(
      int status, String decision, String outcome) {
    var budget = new Schedule429JavaBaselineBudget();
    var report = new Schedule429JavaBaselineReport();
    var calls = new AtomicInteger();
    Runnable attempt =
        () -> {
          calls.incrementAndGet();
          if (status == 0) throw VulcanHttpException.transportFailure("private provider text");
          if (status == -1) throw VulcanHttpException.unexpectedHtml("private provider text");
          if (status == -2) throw new VulcanProtocolException("private provider text");
          if (status != 200)
            throw VulcanHttpException.responseFailure("private provider text", status);
        };
    VulcanSchedule429JavaBaseline.runJava(report, budget, attempt);
    assertThatThrownBy(() -> VulcanSchedule429JavaBaseline.runJava(report, budget, attempt))
        .isInstanceOf(Schedule429Failure.class);
    assertThat(calls).hasValue(1);
    assertThat(budget.javaRequests()).isEqualTo(1);
    assertThat(render(report))
        .contains(
            "decisionCase=" + decision,
            "javaOutcome=" + outcome,
            "browserScheduleRequests=0",
            "retries=0")
        .doesNotContain("private provider text");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 120, 86400, Long.MAX_VALUE})
  void retryAfterOnlyEmitsNonNegativeDuration(long seconds) {
    var report = new Schedule429JavaBaselineReport();
    VulcanSchedule429JavaBaseline.runJava(
        report,
        new Schedule429JavaBaselineBudget(),
        () -> {
          throw VulcanHttpException.responseFailure(
              "raw forbidden header", 429, Duration.ofSeconds(seconds));
        });
    assertThat(render(report))
        .contains("retryAfterPresent=true", "retryAfterSeconds=" + seconds)
        .doesNotContain("raw forbidden header");
  }

  @Test
  void absentRetryAfterAndRawExceptionsAreSanitized() {
    var report = new Schedule429JavaBaselineReport();
    VulcanSchedule429JavaBaseline.runJava(
        report,
        new Schedule429JavaBaselineBudget(),
        () -> {
          throw VulcanHttpException.responseFailure("private", 429);
        });
    report.failure(new IllegalArgumentException("https://private.example/cookie=value"));
    assertThat(render(report))
        .contains("retryAfterPresent=false", "failureCategory=INTERNAL_INVARIANT")
        .doesNotContain("retryAfterSeconds", "private", "cookie=value");
  }

  @Test
  void exactDriftDetectsChangesWithinSameRefererCategoryAndCookieReordering() {
    URI base = URI.create("https://school.vulcan.net.pl/private/");
    var before =
        new VulcanSessionMaterial(
            base, base.resolve("One.mvc"), "token1", "guid1", "First=one; Second=two");
    var after =
        new VulcanSessionMaterial(
            base, base.resolve("Two.mvc"), "token2", "guid2", "Second=two; First=one");
    var report = new Schedule429JavaBaselineReport();
    VulcanSchedule429JavaBaseline.reportDrift(report, before, after);
    assertThat(render(report))
        .contains(
            "applicationBaseChanged=false",
            "refererChangedExact=true",
            "verificationTokenChanged=true",
            "appGuidChanged=true",
            "cookieMaterialChanged=true",
            "cookieCountChanged=false",
            "postLoginCookieCount=2",
            "verifiedCookieCount=2",
            "postLoginRefererContext=OTHER_ALLOWED",
            "verifiedRefererContext=OTHER_ALLOWED")
        .doesNotContain(
            "First",
            "Second",
            "token1",
            "token2",
            "guid1",
            "guid2",
            "One.mvc",
            "Two.mvc",
            "/private/");
    var upperCaseHost =
        new VulcanSessionMaterial(
            base,
            URI.create("https://SCHOOL.vulcan.net.pl/private/One.mvc"),
            "token1",
            "guid1",
            "First=one; Second=two");
    VulcanSchedule429JavaBaseline.reportDrift(report, before, upperCaseHost);
    assertThat(render(report)).contains("refererChangedExact=true");
    var changedBase = base.resolve("changed/");
    VulcanSchedule429JavaBaseline.reportDrift(
        report, before, new VulcanSessionMaterial(changedBase, changedBase, "t", "g", "New=value"));
    assertThat(render(report))
        .contains("applicationBaseChanged=true", "cookieCountChanged=true", "verifiedCookieCount=1")
        .doesNotContain("New=value");
    VulcanSchedule429JavaBaseline.reportDrift(report, before, before);
    assertThat(render(report))
        .contains(
            "refererChangedExact=false",
            "verificationTokenChanged=false",
            "appGuidChanged=false",
            "cookieMaterialChanged=false");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void realProductionLoopbackUsesOriginalMaterialAndParsesRetryAfter(boolean httpDate)
      throws Exception {
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/synthetic/");
    final var postLogin =
        new VulcanSessionMaterial(base, base, "original-token", "original-guid", "Original=before");
    var verified =
        new VulcanSessionMaterial(
            base, base.resolve("Other.mvc"), "rotated-token", "rotated-guid", "Rotated=after");
    var calls = new AtomicInteger();
    var originalUsed = new AtomicBoolean();
    server.createContext(
        "/synthetic/PlanLekcji.mvc/GetPlanLekcjiContext",
        exchange -> {
          try (exchange) {
            calls.incrementAndGet();
            var headers = exchange.getRequestHeaders();
            originalUsed.set(
                postLogin.cookieHeader().equals(headers.getFirst("Cookie"))
                    && postLogin.appGuid().equals(headers.getFirst("X-V-AppGuid"))
                    && postLogin
                        .requestVerificationToken()
                        .equals(headers.getFirst("X-V-RequestVerificationToken"))
                    && postLogin.refererUri().toString().equals(headers.getFirst("Referer")));
            exchange.getRequestBody().readAllBytes();
            String retry =
                httpDate
                    ? java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(120))
                    : "120";
            exchange.getResponseHeaders().set("Retry-After", retry);
            exchange.getResponseHeaders().set("Set-Cookie", "Original=rotated; Path=/");
            exchange.sendResponseHeaders(429, -1);
          }
        });
    server.start();
    try {
      var report = new Schedule429JavaBaselineReport();
      var budget = new Schedule429JavaBaselineBudget();
      VulcanSchedule429JavaBaseline.reportDrift(report, postLogin, verified);
      VulcanSchedule429JavaBaseline.runFromPostLogin(
          report, budget, postLogin, 1, LocalDate.of(2026, 8, 31));
      assertThat(originalUsed).isTrue();
      assertThat(calls).hasValue(1);
      assertThat(postLogin.cookieHeader()).isEqualTo("Original=before");
      String text = render(report);
      assertThat(text)
          .contains(
              "decisionCase=J1",
              "retryAfterPresent=true",
              "javaMaterialContext=PRE_VERIFICATION_POST_LOGIN")
          .doesNotContain("GMT", "Original", "Rotated", "original-token", "rotated-guid");
      var matcher = java.util.regex.Pattern.compile("retryAfterSeconds=(\\d+)").matcher(text);
      assertThat(matcher.find()).isTrue();
      assertThat(Long.parseLong(matcher.group(1))).isBetween(115L, 120L);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void finiteSchemaRejectsRawDataAndBudgetViolations() {
    for (String key : Schedule429JavaBaselineReport.SCHEMA.keySet())
      for (String value :
          new String[] {
            "https://private.example/path",
            "Cookie=value",
            "Sat, 05 Sep 2026 18:00:00 GMT",
            "secret\nresult=SUCCESS"
          })
        assertThatThrownBy(() -> new Schedule429JavaBaselineReport().put(key, value))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Schedule429JavaBaselineReport().put("browserScheduleRequests", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Schedule429JavaBaselineReport().put("javaScheduleRequests", 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Schedule429JavaBaselineReport().put("retryAfterSeconds", -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void flowRetainsPreVerificationMaterialAndHasNoBrowserControlOrRetry() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "src/test/java/io/github/bohdankordon/vulcanschedulemonitor/devsmoke/VulcanSchedule429JavaBaseline.java"));
    assertThat(source)
        .contains(
            "final VulcanSessionMaterial postLogin",
            "postLogin = browser.authenticate(request)",
            "new VulcanClient(VulcanSession.fromMaterial(postLogin))",
            "browser.finishAuthenticationOnly()")
        .doesNotContain(
            "browser.control(",
            "Resilient",
            "fromMaterial(verified",
            "SpringApplication.run",
            "System.getenv",
            "postPlan");
    assertThat(source.indexOf("postLogin = browser.authenticate"))
        .isLessThan(source.indexOf("verifyAndDiscover(postLogin)"));
    assertThat(source.indexOf("browserClosedBeforeVerification"))
        .isLessThan(source.indexOf("verifyAndDiscover(postLogin)"));
    String script = Files.readString(Path.of("scripts/vulcan-real-smoke.ps1"));
    assertThat(script)
        .contains("stage = '" + Schedule429JavaBaselineReport.SCHEMA.get("stage") + "'");
  }

  static String render(Schedule429JavaBaselineReport report) {
    var bytes = new ByteArrayOutputStream();
    report.print(new PrintStream(bytes));
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
