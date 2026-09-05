package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Failure.Stage.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright.Schedule429Browser;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Set;
import org.slf4j.LoggerFactory;

/** Explicit one-invocation diagnostic. Not a Maven test; absent from the production jar. */
public final class VulcanSchedule429Investigation {
  static boolean authorized(String[] args) {
    return args.length == 1 && args[0].equals("--authorized-schedule-429-investigation");
  }

  public static void main(String[] args) {
    PrintStream output = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    Schedule429Report report = new Schedule429Report();
    Schedule429Budget budget = new Schedule429Budget();
    int exit = 1;
    try {
      LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
      logging.reset();
      logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
      java.util.logging.LogManager.getLogManager().reset();
      VulcanRealSmoke.disableBackgroundServices();
      if (!authorized(args)) {
        report.put("category", "INVALID_INPUT");
      } else {
        // Loopback-only synthetic calibration. No secret or provider URL is used by this probe.
        report.stage(JAVA_TRANSPORT_CALIBRATION);
        var javaShape = Schedule429JavaShape.measure();
        report.stage(INVESTIGATION_INPUT);
        try (SmokeInput input = SmokeInput.read(System.in)) {
          var portal = new PortalUrlValidator().validate(input.portal);
          try (var request = new VulcanLoginRequest(portal, input.login, input.password);
              var browser = new Schedule429Browser(new SmokeDiagnostics(), report, budget)) {
            try {
              report.stage(AUTHENTICATED_BROWSER_READY);
              final var postLogin = browser.authenticate(request);
              report.put(
                  "postLoginRefererContext",
                  Schedule429Structure.referer(postLogin.refererUri().toASCIIString()));
              Schedule429Structure.initialCookies(report, postLogin);
              reportJavaShape(report, javaShape, postLogin);
              report.put("javaMaterialContext", "POST_LOGIN_PROJECTION");
              report.stage(CATALOG_READY);
              var verified = new DefaultVulcanSessionVerifier().verifyAndDiscover(postLogin);
              report.put("classCount", verified.classes().size());
              Schedule429Structure.verificationDrift(report, postLogin, verified.sessionMaterial());
              LocalDate week =
                  LocalDate.now(ZoneId.of("Europe/Warsaw"))
                      .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
              browser.control(postLogin, verified.classes(), week);
              if (browser.scheduleStatus() == 429) {
                report.put("decisionCase", "1");
                report.put("category", "BROWSER_RATE_LIMITED");
                report.put("javaOutcome", "NOT_RUN");
              } else if (!budget.javaPermitted()) {
                report.put("category", "BROWSER_OTHER_FAILURE");
                report.put("javaOutcome", "NOT_RUN");
              } else {
                report.stage(JAVA_COMPARISON_SETUP);
                compareForms(report, browser.browserForm(), javaShape.form());
                compareJavaFromPostLogin(report, budget, postLogin, browser.journal(), week);
                exit = 0;
              }
            } catch (Throwable failure) {
              report.fail(failure);
              throw failure;
            }
          }
        }
      }
    } catch (VulcanAuthenticationException failure) {
      report.fail(failure);
      report.put("category", failure.category().name());
    } catch (Throwable failure) {
      report.fail(failure);
      report.put("category", "HARNESS_FAILURE");
    }
    budget.report(report);
    exit = report.compared() ? 0 : 1;
    report.put("result", exit == 0 ? "SUCCESS" : "FAIL");
    report.print(output);
    System.exit(exit);
  }

  static void compareJavaFromPostLogin(
      Schedule429Report report,
      Schedule429Budget budget,
      io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial postLogin,
      long journal,
      LocalDate week) {
    report.stage(JAVA_COMPARISON_SETUP);
    // This immutable material was captured BEFORE catalog verification and the browser request.
    var client = new VulcanClient(VulcanSession.fromMaterial(postLogin));
    report.stage(JAVA_COMPARISON);
    compareJava(report, budget, () -> client.getWeekSchedule(journal, week));
    report.put("javaMaterialContext", "PRE_REQUEST_POST_LOGIN");
  }

  static void compareJava(
      Schedule429Report report, Schedule429Budget budget, Runnable javaRequest) {
    budget.takeJavaPermit();
    report.put("javaPermitted", true);
    try {
      javaRequest.run();
      report.put("javaOutcome", "SUCCESS");
      report.put("java.statusFamily", "2xx");
      report.put("java.status429", false);
      report.put("java.contentFamily", "json");
      report.put("decisionCase", "2");
    } catch (VulcanHttpException failure) {
      report.put("javaOutcome", failure.category().name());
      report.put(
          "java.statusFamily",
          failure.statusCode() == null
              ? "UNAVAILABLE"
              : Schedule429Structure.statusFamily(failure.statusCode()));
      boolean limited = Integer.valueOf(429).equals(failure.statusCode());
      report.put("java.status429", limited);
      report.put("decisionCase", limited ? "3" : "4");
    } catch (VulcanProtocolException failure) {
      report.put("javaOutcome", "PROTOCOL_FAILURE");
      report.put("decisionCase", "4");
    }
    report.put("category", "COMPARED");
    report.put("result", "SUCCESS");
  }

  private static void reportJavaShape(
      Schedule429Report report,
      Schedule429JavaShape.Shape javaShape,
      io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial material) {
    var headers = new HashMap<>(javaShape.headers());
    // Projection for diagnostics only. Actual production request headers are never changed.
    headers
        .keySet()
        .removeIf(
            key -> Set.of("cookie", "referer").contains(key.toLowerCase(java.util.Locale.ROOT)));
    headers.put("Cookie", material.cookieHeader());
    headers.put("Referer", material.refererUri().toASCIIString());
    Schedule429Structure.headers(report, "java", headers);
    report.put("java.method", "POST");
    Schedule429Structure.formReport(report, "java", javaShape.form());
  }

  private static void compareForms(
      Schedule429Report r, Schedule429Structure.FormFacts b, Schedule429Structure.FormFacts j) {
    r.put("sameFieldSet", b.fields() && j.fields());
    r.put(
        "sameTimestampShape",
        b.fromIso() == j.fromIso() && b.toIso() == j.toIso() && b.anchorIso() == j.anchorIso());
    r.put("sameWeekBoundarySemantics", b.week() && j.week());
    r.put("sameDataSemanticPosition", b.anchorAtStart() == j.anchorAtStart());
    r.put("sameFormEncoding", b.encoded() && j.encoded());
  }
}
