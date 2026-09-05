package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static io.github.bohdankordon.vulcanschedulemonitor.devsmoke.NativeSessionBaselineTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class NativeSessionCookieObservationTest {
  private static final String A = "SUPER_SECRET_COOKIE_A", B = "SUPER_SECRET_COOKIE_B";

  static Stream<Arguments> responses() {
    return Arrays.stream(NativeSessionBaselineReport.Variant.values())
        .flatMap(
            variant ->
                Stream.of(
                    Arguments.of(variant, 200, "json", "NONE", "SUCCESS", 2, false),
                    Arguments.of(variant, 200, "json", "ROTATE", "SUCCESS", 2, true),
                    Arguments.of(variant, 200, "json", "ADD", "SUCCESS", 3, true),
                    Arguments.of(variant, 200, "json", "REMOVE", "SUCCESS", 1, true),
                    Arguments.of(variant, 200, "json", "REORDER", "SUCCESS", 2, false),
                    Arguments.of(variant, 429, "json", "NONE", "RATE_LIMITED", 2, false),
                    Arguments.of(variant, 429, "json", "ROTATE", "RATE_LIMITED", 2, true),
                    Arguments.of(variant, 200, "html", "ROTATE", "UNEXPECTED_HTML", 2, true),
                    Arguments.of(variant, 503, "json", "ROTATE", "SERVER_ERROR", 2, true),
                    Arguments.of(variant, 302, "html", "ROTATE", "SESSION_REDIRECT", 2, true),
                    Arguments.of(
                        variant, 401, "json", "ROTATE", "AUTHENTICATION_REQUIRED", 2, true),
                    Arguments.of(
                        variant, 403, "json", "ROTATE", "AUTHENTICATION_REQUIRED", 2, true),
                    Arguments.of(variant, 404, "json", "ROTATE", "PERMANENT_HTTP", 2, true),
                    Arguments.of(
                        variant, 200, "malformed", "ROTATE", "PROTOCOL_FAILURE", 2, true)));
  }

  @ParameterizedTest
  @MethodSource("responses")
  void realProductionJdkCookieHandlerIsObservedEvenWhenResponseThrows(
      NativeSessionBaselineReport.Variant variant,
      int status,
      String content,
      String mutation,
      String outcome,
      int countAfter,
      boolean changed)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var requests = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            var headers = exchange.getResponseHeaders();
            String path = "; Path=/SECRET_TENANT/";
            switch (mutation) {
              case "ROTATE" -> headers.add("Set-Cookie", "CookieOne=" + B + path);
              case "ADD" -> headers.add("Set-Cookie", "CookieThree=" + B + path);
              case "REMOVE" -> headers.add("Set-Cookie", "CookieOne=" + A + path + "; Max-Age=0");
              case "REORDER" -> {
                headers.add("Set-Cookie", "CookieTwo=" + A + path);
                headers.add("Set-Cookie", "CookieOne=" + A + path);
              }
              default -> {}
            }
            String body =
                switch (content) {
                  case "html" -> "<html>" + B + "</html>";
                  case "malformed" -> "{" + B;
                  default ->
                      "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}";
                };
            headers.set("Content-Type", content.equals("html") ? "text/html" : "application/json");
            if (status == 302) headers.set("Location", "/must-not-follow");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_TENANT/";
    String[] values = fields(base);
    values[4] = "CookieOne=" + A + "; CookieTwo=" + A;
    try (var input = input(values)) {
      var material = input.validate(URI.create(values[0]));
      var report = new NativeSessionBaselineReport(variant);
      var permit = new VulcanNativeSessionJavaBaseline.Permit();
      VulcanNativeSessionJavaBaseline.execute(material, permit, report);
      assertThat(report.facts())
          .containsEntry("javaOutcome", outcome)
          .containsEntry("session.cookieCountBefore", 2)
          .containsEntry("session.cookieCountAfter", countAfter)
          .containsEntry("session.cookieCountChanged", countAfter != 2)
          .containsEntry("session.cookieMaterialChanged", changed)
          .containsEntry("javaScheduleRequests", 1)
          .containsEntry("retries", 0)
          .containsEntry(
              "acceptInjected", variant == NativeSessionBaselineReport.Variant.ACCEPT_STAR_STAR);
      safe(report.json());
      assertThat(report.json()).doesNotContain(A, B, "CookieOne", "CookieTwo", "CookieThree");
      var denied = new NativeSessionBaselineReport(variant);
      VulcanNativeSessionJavaBaseline.execute(material, permit, denied);
      assertThat(denied.facts()).containsEntry("category", "BUDGET_EXHAUSTED");
      unavailable(denied, true);
      assertThat(requests.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void orderingOnlyIsIgnoredButPairMultiplicityAndValuesMatter() {
    for (String after :
        List.of(
            "two=" + B + "; one=" + A,
            "one=" + B + "; two=" + B,
            "one=" + A + "; one=" + A + "; two=" + B)) {
      var calls = new AtomicInteger();
      var report = new NativeSessionBaselineReport();
      NativeSessionCookieObservation.observe(
          () -> cookieMaterial(calls.getAndIncrement() == 0 ? "one=" + A + "; two=" + B : after),
          report,
          () -> {});
      assertThat(calls.get()).isEqualTo(2);
      assertThat(report.facts())
          .containsEntry("session.cookieMaterialChanged", !after.startsWith("two="));
      safe(report.json());
    }
  }

  @Test
  void transportFailureStillSnapshotsInFinallyButCannotEstablishDispatch() {
    var calls = new AtomicInteger();
    var requests = new AtomicInteger();
    var report = new NativeSessionBaselineReport();
    VulcanNativeSessionJavaBaseline.runOnce(
        new VulcanNativeSessionJavaBaseline.Permit(),
        report,
        () ->
            NativeSessionCookieObservation.observe(
                () -> {
                  calls.incrementAndGet();
                  return cookieMaterial("one=" + A);
                },
                report,
                () -> {
                  requests.incrementAndGet();
                  throw VulcanHttpException.transportFailure("GetPlanLekcjiContext");
                }));
    assertThat(calls.get()).isEqualTo(2);
    assertThat(requests.get()).isEqualTo(1);
    assertThat(report.facts())
        .containsEntry("javaOutcome", "TRANSPORT_ERROR")
        .containsEntry("session.cookieCountBefore", 1);
    unavailable(report, false);
  }

  @Test
  void preflightFailureDoesNotFabricateAnUnchangedSession() {
    var values = fields("http://127.0.0.1:9/SECRET_TENANT/");
    values[7] = "2026-09-05T12:00:00";
    try (var input = input(values)) {
      var report = new NativeSessionBaselineReport();
      VulcanNativeSessionJavaBaseline.execute(
          input.validate(URI.create(values[0])),
          new VulcanNativeSessionJavaBaseline.Permit(),
          report);
      assertThat(report.facts())
          .containsEntry("category", "FORM_MISMATCH")
          .containsEntry("javaRequestAttempted", false);
      unavailable(report, true);
    }
  }

  @Test
  void refusedLoopbackConnectionKeepsPostObservationUnavailable() throws Exception {
    int port;
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      port = socket.getLocalPort();
    }
    var values = fields("http://127.0.0.1:" + port + "/SECRET_TENANT/");
    try (var input = input(values)) {
      var report = new NativeSessionBaselineReport();
      VulcanNativeSessionJavaBaseline.execute(
          input.validate(URI.create(values[0])),
          new VulcanNativeSessionJavaBaseline.Permit(),
          report);
      assertThat(report.facts())
          .containsEntry("javaOutcome", "TRANSPORT_ERROR")
          .containsEntry("session.cookieCountBefore", 2)
          .containsEntry("retries", 0);
      unavailable(report, false);
    }
  }

  @ParameterizedTest
  @EnumSource(NativeSessionBaselineReport.Variant.class)
  void rawSnapshotAndRequestExceptionsNeverReachStdoutOrStderr(
      NativeSessionBaselineReport.Variant variant) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    PrintStream previousOut = System.out, previousErr = System.err;
    try (var capturedOut = new PrintStream(out);
        var capturedErr = new PrintStream(err)) {
      System.setOut(capturedOut);
      System.setErr(capturedErr);
      var report = new NativeSessionBaselineReport(variant);
      VulcanNativeSessionJavaBaseline.runOnce(
          new VulcanNativeSessionJavaBaseline.Permit(),
          report,
          () ->
              NativeSessionCookieObservation.observe(
                  () -> {
                    throw new IllegalArgumentException(A);
                  },
                  report,
                  () -> {
                    throw new IllegalStateException(B);
                  }));
      System.out.println(report.json());
    } finally {
      System.setOut(previousOut);
      System.setErr(previousErr);
    }
    safe(out.toString(StandardCharsets.UTF_8));
    safe(err.toString(StandardCharsets.UTF_8));
    assertThat(out.toString(StandardCharsets.UTF_8)).contains("HARNESS_FAILURE");
    assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void failedSnapshotNeverMasksScheduleClassification(int failingSnapshot) {
    var calls = new AtomicInteger();
    var report = new NativeSessionBaselineReport();
    VulcanNativeSessionJavaBaseline.runOnce(
        new VulcanNativeSessionJavaBaseline.Permit(),
        report,
        () ->
            NativeSessionCookieObservation.observe(
                () -> {
                  if (calls.getAndIncrement() == failingSnapshot)
                    throw new IllegalArgumentException(A + B);
                  return cookieMaterial("one=" + A);
                },
                report,
                () -> {
                  throw VulcanHttpException.responseFailure("GetPlanLekcjiContext", 429);
                }));
    assertThat(calls.get()).isEqualTo(2);
    assertThat(report.facts()).containsEntry("javaOutcome", "RATE_LIMITED");
    unavailable(report, failingSnapshot == 0);
    safe(report.json());
  }

  @Test
  void guardsRejectRawCookieDetailsAndInvalidCounts() {
    var report = new NativeSessionBaselineReport();
    for (String key :
        List.of(
            "session.cookieNames", "session.cookieCountAfter", "session.cookieMaterialChanged")) {
      assertThatThrownBy(() -> report.put(key, A)).hasMessage("UNSAFE_OUTPUT_GUARD").hasNoCause();
    }
    assertThatThrownBy(() -> report.put("session.cookieCountBefore", 1001))
        .hasMessage("UNSAFE_OUTPUT_GUARD");
    safe(report.json());
  }

  @Test
  void powershellCookieOutputGuards() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    var result =
        run(
            new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/tests/vulcan-native-session-cookie-observation.Tests.ps1"),
            new byte[0]);
    assertThat(result.exit()).isZero();
    assertThat(result.out())
        .contains("Synthetic cookie observation PowerShell contracts passed: 9 cases.");
  }

  private static VulcanSessionMaterial cookieMaterial(String cookies) {
    return new VulcanSessionMaterial(
        URI.create(BASE), URI.create(BASE + "Other.mvc"), TOKEN, GUID, cookies);
  }

  private static void unavailable(NativeSessionBaselineReport report, boolean before) {
    if (before)
      assertThat(report.facts()).containsEntry("session.cookieCountBefore", "UNAVAILABLE");
    assertThat(report.facts())
        .containsEntry("session.cookieCountAfter", "UNAVAILABLE")
        .containsEntry("session.cookieCountChanged", "UNAVAILABLE")
        .containsEntry("session.cookieMaterialChanged", "UNAVAILABLE");
  }
}
