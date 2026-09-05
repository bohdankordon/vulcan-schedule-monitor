package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429JavaBaselineReport.Stage.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright.Schedule429Browser;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import org.slf4j.LoggerFactory;

/** Explicit Java-only experiment. Test source, no Spring, database, monitoring, or retries. */
public final class VulcanSchedule429JavaBaseline {
  static boolean authorized(String[] args) {
    return args.length == 1 && args[0].equals("--authorized-schedule-429-java-baseline");
  }

  public static void main(String[] args) {
    PrintStream output = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    var report = new Schedule429JavaBaselineReport();
    var budget = new Schedule429JavaBaselineBudget();
    try {
      var logging = (LoggerContext) LoggerFactory.getILoggerFactory();
      logging.reset();
      logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
      java.util.logging.LogManager.getLogManager().reset();
      VulcanRealSmoke.disableBackgroundServices();
      if (!authorized(args)) report.put("category", "INVALID_INPUT");
      else
        try (var input = SmokeInput.read(System.in)) {
          var portal = new PortalUrlValidator().validate(input.portal);
          final VulcanSessionMaterial postLogin;
          report.stage(AUTHENTICATION);
          try (var request = new VulcanLoginRequest(portal, input.login, input.password);
              var browser =
                  Schedule429Browser.authenticationOnly(
                      new SmokeDiagnostics(),
                      new Schedule429Report(),
                      budget::unexpectedBrowserSchedule)) {
            postLogin = browser.authenticate(request);
            browser.finishAuthenticationOnly();
            budget.requireQuietBrowser();
          }
          // Chromium is closed before verifier or Java schedule traffic can occur.
          budget.requireQuietBrowser();
          report.put("browserClosedBeforeVerification", true);
          report.stage(CATALOG_DISCOVERY);
          var verified = new DefaultVulcanSessionVerifier().verifyAndDiscover(postLogin);
          report.put("classCount", verified.classes().size());
          reportDrift(report, postLogin, verified.sessionMaterial());
          report.stage(TARGET_SELECTION);
          if (verified.classes().isEmpty())
            throw new Schedule429Failure(Schedule429Failure.Category.INTERNAL_INVARIANT);
          long selectedJournal = verified.classes().getFirst().journalId();
          LocalDate currentWeek =
              LocalDate.now(ZoneId.of("Europe/Warsaw"))
                  .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
          runFromPostLogin(report, budget, postLogin, selectedJournal, currentWeek);
        }
    } catch (VulcanAuthenticationException failure) {
      report.failure(failure);
      report.put("category", failure.category().name());
    } catch (Throwable failure) {
      report.failure(failure);
      report.put("category", "HARNESS_FAILURE");
    }
    if (budget.unexpectedBrowser()) report.put("category", "UNEXPECTED_BROWSER_SCHEDULE_TRAFFIC");
    report.put("unexpectedBrowserScheduleTraffic", budget.unexpectedBrowser());
    report.put("javaScheduleRequests", budget.javaRequests());
    report.print(output);
    System.exit(report.succeeded() ? 0 : 1);
  }

  static void runFromPostLogin(
      Schedule429JavaBaselineReport report,
      Schedule429JavaBaselineBudget budget,
      VulcanSessionMaterial postLogin,
      long selectedJournal,
      LocalDate currentWeek) {
    report.stage(JAVA_BASELINE_SETUP);
    budget.requireQuietBrowser();
    var client = new VulcanClient(VulcanSession.fromMaterial(postLogin));
    runJava(report, budget, () -> client.getWeekSchedule(selectedJournal, currentWeek));
  }

  static void runJava(
      Schedule429JavaBaselineReport report,
      Schedule429JavaBaselineBudget budget,
      Runnable request) {
    report.stage(JAVA_BASELINE);
    budget.takeJavaPermit();
    report.put("javaScheduleRequests", budget.javaRequests());
    report.put("javaMaterialContext", "PRE_VERIFICATION_POST_LOGIN");
    try {
      request.run();
      report.put("javaOutcome", "SUCCESS");
      report.put("java.statusFamily", "2xx");
      report.put("java.status429", false);
      report.put("decisionCase", "J2");
      report.put("result", "SUCCESS");
    } catch (VulcanHttpException failure) {
      report.put("javaOutcome", failure.category().name());
      report.put(
          "java.statusFamily",
          failure.statusCode() == null
              ? "UNAVAILABLE"
              : Schedule429Structure.statusFamily(failure.statusCode()));
      boolean rateLimited = Integer.valueOf(429).equals(failure.statusCode());
      report.put("java.status429", rateLimited);
      report.put(
          "decisionCase",
          rateLimited
              ? "J1"
              : switch (failure.category()) {
                case AUTHENTICATION_REQUIRED, SESSION_REDIRECT, UNEXPECTED_HTML -> "J3";
                default -> "J4";
              });
      if (rateLimited) {
        report.put("retryAfterPresent", failure.retryAfter().isPresent());
        failure
            .retryAfter()
            .ifPresent(
                duration -> report.put("retryAfterSeconds", Math.max(0L, duration.getSeconds())));
      }
    } catch (VulcanProtocolException failure) {
      report.put("javaOutcome", "PROTOCOL_FAILURE");
      report.put("decisionCase", "J4");
    }
    report.put("category", "BASELINE_COMPLETED");
  }

  static void reportDrift(
      Schedule429JavaBaselineReport report,
      VulcanSessionMaterial before,
      VulcanSessionMaterial after) {
    report.put(
        "applicationBaseChanged", !before.applicationBaseUri().equals(after.applicationBaseUri()));
    report.put(
        "refererChangedExact",
        !before.refererUri().toASCIIString().equals(after.refererUri().toASCIIString()));
    report.put(
        "verificationTokenChanged",
        !before.requestVerificationToken().equals(after.requestVerificationToken()));
    report.put("appGuidChanged", !before.appGuid().equals(after.appGuid()));
    // Exact material comparison intentionally includes cookie order/serialization differences.
    report.put("cookieMaterialChanged", !before.cookieHeader().equals(after.cookieHeader()));
    int beforeCount = cookieCount(before.cookieHeader()),
        afterCount = cookieCount(after.cookieHeader());
    report.put("cookieCountChanged", beforeCount != afterCount);
    report.put("postLoginCookieCount", beforeCount);
    report.put("verifiedCookieCount", afterCount);
    report.put(
        "postLoginRefererContext",
        Schedule429Structure.referer(before.refererUri().toASCIIString()));
    report.put(
        "verifiedRefererContext", Schedule429Structure.referer(after.refererUri().toASCIIString()));
  }

  private static int cookieCount(String header) {
    return (int)
        java.util.Arrays.stream(header.split(";")).filter(pair -> pair.contains("=")).count();
  }
}
