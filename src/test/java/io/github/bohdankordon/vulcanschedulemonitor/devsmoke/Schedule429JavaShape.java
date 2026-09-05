package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/** Measures this JVM's untouched production transport on loopback with synthetic data only. */
final class Schedule429JavaShape {
  record Shape(
      Map<String, String> headers,
      Schedule429Structure.FormFacts form,
      String actualObservedHttpVersion,
      String clientPreferredVersion) {
    Map<String, Object> fingerprint() {
      var report = new LinkedHashMap<>(Schedule429Fingerprint.headers(headers));
      report.put("schemaVersion", 2);
      report.put("profileSource", "JAVA_LOOPBACK");
      report.put("actualObservedHttpVersion", actualObservedHttpVersion);
      report.put("clientPreferredVersion", clientPreferredVersion);
      report.put("form.exactExpectedFieldSet", form.fields());
      report.put("form.weekIsMondayToSunday", form.week());
      report.put("form.dataWithinWeek", form.anchorAtStart() && form.week());
      report.put("form.urlEncoded", form.encoded());
      report.put("form.dataOdShape", form.fromIso() ? "ISO_T_DATETIME" : "OTHER");
      report.put("form.dataDoShape", form.toIso() ? "ISO_T_DATETIME" : "OTHER");
      report.put("form.dataShape", form.anchorIso() ? "ISO_T_DATETIME" : "OTHER");
      Schedule429Fingerprint.validateProjection(report);
      return Map.copyOf(report);
    }
  }

  /** Safe stdout only. No credentials, provider targets, or input files accepted. */
  public static void main(String[] args) {
    try {
      if (args.length != 0) throw new IllegalArgumentException("INVALID_INPUT");
      System.out.println(new ObjectMapper().writeValueAsString(measure().fingerprint()));
    } catch (Exception ignored) {
      System.out.println("{\"schemaVersion\":2,\"result\":\"PROFILE_FAILURE\"}");
      System.exit(1);
    }
  }

  static String clientPreference(VulcanClient client) {
    try {
      Object adapter = ReflectionTestUtils.getField(client, "scheduleAdapter");
      Object transport = ReflectionTestUtils.getField(adapter, "transport");
      Object restClient = ReflectionTestUtils.getField(transport, "restClient");
      Object factory = ReflectionTestUtils.getField(restClient, "clientRequestFactory");
      Object httpClient = ReflectionTestUtils.getField(factory, "httpClient");
      if (httpClient instanceof HttpClient actual) return actual.version().name();
    } catch (RuntimeException ignored) {
      // Diagnostic-only read of the actual configured instance; never change it.
    }
    return "UNKNOWN";
  }

  static Shape measure() throws Exception {
    LocalDate week = LocalDate.of(2026, 8, 31);
    AtomicReference<Shape> captured = new AtomicReference<>();
    AtomicReference<String> preference = new AtomicReference<>("UNKNOWN");
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/synthetic/PlanLekcji.mvc/GetPlanLekcjiContext",
        exchange -> {
          try (exchange) {
            Map<String, String> headers = new HashMap<>();
            exchange
                .getRequestHeaders()
                .forEach((key, values) -> headers.put(key, String.join("; ", values)));
            String body =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(
                new Shape(
                    Map.copyOf(headers),
                    Schedule429Structure.form(
                        exchange.getRequestMethod(),
                        body,
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        week),
                    Schedule429Fingerprint.protocol(exchange.getProtocol()),
                    preference.get()));
            byte[] response =
                "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    try {
      server.start();
      URI base =
          new URI(
              "http",
              null,
              server.getAddress().getAddress().getHostAddress(),
              server.getAddress().getPort(),
              "/synthetic/",
              null,
              null);
      var session =
          VulcanSession.fromBrowserSession(
              base, "synthetic-token", "synthetic-guid", "SyntheticCookie=value", base);
      var client = new VulcanClient(session);
      preference.set(clientPreference(client));
      client.getWeekSchedule(1, week);
      return java.util.Objects.requireNonNull(captured.get());
    } finally {
      server.stop(0);
    }
  }
}
