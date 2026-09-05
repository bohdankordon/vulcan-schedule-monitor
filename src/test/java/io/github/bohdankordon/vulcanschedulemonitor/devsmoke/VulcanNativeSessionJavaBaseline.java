package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;

/** Explicit native-session diagnostic. No browser, database, scheduler, auth, or retry wrapper. */
public final class VulcanNativeSessionJavaBaseline {
  static final class Permit {
    private final AtomicBoolean used = new AtomicBoolean();

    void take() {
      if (!used.compareAndSet(false, true)) throw new IllegalStateException("BUDGET_EXHAUSTED");
    }
  }

  public static void main(String[] args) {
    PrintStream output = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    boolean acceptVariant =
        args.length == 1
            && Set.of(
                    "--authorized-native-session-java-accept-baseline",
                    "--validate-native-session-accept-input")
                .contains(args[0]);
    var report =
        new NativeSessionBaselineReport(
            acceptVariant
                ? NativeSessionBaselineReport.Variant.ACCEPT_STAR_STAR
                : NativeSessionBaselineReport.Variant.UNTOUCHED);
    try {
      var logging = (LoggerContext) LoggerFactory.getILoggerFactory();
      logging.reset();
      logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
      java.util.logging.LogManager.getLogManager().reset();
      boolean validationOnly =
          args.length == 1
              && Set.of("--validate-native-session-input", "--validate-native-session-accept-input")
                  .contains(args[0]);
      if (!validationOnly
          && (args.length != 1
              || !Set.of(
                      "--authorized-native-session-java-baseline",
                      "--authorized-native-session-java-accept-baseline")
                  .contains(args[0]))) report.put("category", "NOT_AUTHORIZED");
      else
        try (var input = NativeSessionBaselineInput.read(System.in)) {
          var material = input.validate();
          if (validationOnly) {
            if (preflight(material, report)) report.put("category", "VALIDATION_ONLY");
          } else execute(material, new Permit(), report);
        }
    } catch (IllegalArgumentException ignored) {
      report.put("category", "INVALID_INPUT");
    } catch (Throwable ignored) {
      report.put("category", "HARNESS_FAILURE");
    }
    output.println(report.json());
    System.exit("SUCCESS".equals(report.facts().get("result")) ? 0 : 1);
  }

  /**
   * Separate synthetic client with an in-memory sink: no socket, agent, or real client mutation.
   */
  static boolean productionFormMatches(NativeSessionBaselineInput.Material material) {
    AtomicReference<Map<String, String>> built = new AtomicReference<>();
    var syntheticSession =
        VulcanSession.fromBrowserSession(
            URI.create("http://127.0.0.1/synthetic/"), "synthetic", "synthetic", "Synthetic=value");
    var syntheticClient = new VulcanClient(syntheticSession);
    Object adapter = ReflectionTestUtils.getField(syntheticClient, "scheduleAdapter");
    Object transport = ReflectionTestUtils.getField(adapter, "transport");
    Object restClient = ReflectionTestUtils.getField(transport, "restClient");
    ClientHttpRequestFactory sink =
        (uri, method) ->
            new MockClientHttpRequest(method, uri) {
              @Override
              protected org.springframework.http.client.ClientHttpResponse executeInternal() {
                built.set(Schedule429Structure.formValues(getBodyAsString(StandardCharsets.UTF_8)));
                var response =
                    new MockClientHttpResponse(
                        "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}"
                            .getBytes(StandardCharsets.UTF_8),
                        200);
                response.getHeaders().set("Content-Type", "application/json");
                return response;
              }
            };
    ReflectionTestUtils.setField(restClient, "clientRequestFactory", sink);
    syntheticClient.getWeekSchedule(material.journal(), material.dataDate());
    return material.capturedForm().equals(built.get());
  }

  static void execute(
      NativeSessionBaselineInput.Material material,
      Permit permit,
      NativeSessionBaselineReport report) {
    if (!preflight(material, report)) return;
    var client = new VulcanClient(material.session());
    NativeSessionAcceptDecorator decorator =
        report.variant() == NativeSessionBaselineReport.Variant.ACCEPT_STAR_STAR
            ? NativeSessionAcceptDecorator.install(
                client, material.session().resolve(NativeSessionBaselineInput.ENDPOINT))
            : null;
    try {
      runOnce(
          permit, report, () -> client.getWeekSchedule(material.journal(), material.dataDate()));
    } finally {
      if (decorator != null) report.put("acceptInjected", decorator.injected());
    }
  }

  static boolean preflight(
      NativeSessionBaselineInput.Material material, NativeSessionBaselineReport report) {
    report.put("nativeEvidenceValidated", true);
    report.put("session.cookieCount", material.cookieCount());
    report.put("session.refererContext", material.refererContext());
    if (!productionFormMatches(material)) {
      report.put("category", "FORM_MISMATCH");
      return false;
    }
    report.put("form.exactFieldSet", true);
    report.put("form.timestampShapesMatch", true);
    report.put("form.weekSemanticsMatch", true);
    return true;
  }

  static void runOnce(Permit permit, NativeSessionBaselineReport report, Runnable request) {
    try {
      permit.take();
    } catch (IllegalStateException ignored) {
      report.put("category", "BUDGET_EXHAUSTED");
      return;
    }
    report.put("javaRequestAttempted", true);
    report.put("javaScheduleRequests", 1);
    report.put("category", "BASELINE_COMPLETED");
    try {
      request.run();
      report.put("result", "SUCCESS");
      report.put("javaOutcome", "SUCCESS");
      report.put("java.statusFamily", "2xx");
      report.put("java.status429", false);
      report.put("java.contentFamily", "json");
    } catch (VulcanHttpException failure) {
      report.put("javaOutcome", failure.category().name());
      if (failure.statusCode() != null) {
        String family = Schedule429Structure.statusFamily(failure.statusCode());
        if (Set.of("2xx", "3xx", "4xx", "5xx").contains(family))
          report.put("java.statusFamily", family);
        report.put("java.status429", failure.statusCode() == 429);
      }
      if (failure.category() == VulcanFailureCategory.UNEXPECTED_HTML) {
        report.put("java.contentFamily", "html");
        report.put("java.statusFamily", "2xx");
        report.put("java.status429", false);
      }
      if (Integer.valueOf(429).equals(failure.statusCode())) {
        report.put("retryAfterPresent", failure.retryAfter().isPresent());
        failure
            .retryAfter()
            .ifPresent(
                duration ->
                    report.put(
                        "retryAfterSeconds",
                        Math.min(31536000L, Math.max(0L, duration.getSeconds()))));
      }
    } catch (VulcanProtocolException ignored) {
      report.put("javaOutcome", "PROTOCOL_FAILURE");
    } catch (Throwable ignored) {
      report.put("category", "HARNESS_FAILURE");
    }
  }
}
