package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Measures this JVM's untouched production transport on loopback with synthetic data only. */
final class Schedule429JavaShape {
  record Shape(Map<String, String> headers, Schedule429Structure.FormFacts form) {}

  static Shape measure() throws Exception {
    LocalDate week = LocalDate.of(2026, 8, 31);
    AtomicReference<Shape> captured = new AtomicReference<>();
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
                        week)));
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
      new VulcanClient(session).getWeekSchedule(1, week);
      return java.util.Objects.requireNonNull(captured.get());
    } finally {
      server.stop(0);
    }
  }
}
