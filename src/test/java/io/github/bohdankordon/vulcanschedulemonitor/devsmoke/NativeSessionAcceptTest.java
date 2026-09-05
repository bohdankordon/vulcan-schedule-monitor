package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static io.github.bohdankordon.vulcanschedulemonitor.devsmoke.NativeSessionBaselineTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

class NativeSessionAcceptTest {
  record Capture(
      String method, URI uri, String protocol, byte[] body, Map<String, List<String>> headers) {}

  @Test
  void onlyAcceptDiffersOnTheWireWithIdenticalProductionInputs() throws Exception {
    var captured = new CopyOnWriteArrayList<Capture>();
    var server = server(200, "application/json", captured);
    try {
      String base = base(server);
      for (var variant : NativeSessionBaselineReport.Variant.values()) {
        try (var input = input(fields(base))) {
          var report = new NativeSessionBaselineReport(variant);
          VulcanNativeSessionJavaBaseline.execute(
              input.validate(URI.create(fields(base)[0])),
              new VulcanNativeSessionJavaBaseline.Permit(),
              report);
          assertThat(report.facts())
              .containsEntry("javaOutcome", "SUCCESS")
              .containsEntry("variant", variant.name())
              .containsEntry(
                  "acceptInjected",
                  variant == NativeSessionBaselineReport.Variant.ACCEPT_STAR_STAR);
          safe(report.json());
        }
      }
      assertThat(captured).hasSize(2);
      var a = captured.get(0);
      var b = captured.get(1);
      assertThat(a.headers()).doesNotContainKey("accept");
      assertThat(b.headers().get("accept")).containsExactly("*/*");
      assertThat(b.method()).isEqualTo(a.method()).isEqualTo("POST");
      assertThat(b.uri()).isEqualTo(a.uri());
      assertThat(b.protocol()).isEqualTo(a.protocol());
      assertThat(b.body()).isEqualTo(a.body());
      var remaining = new TreeMap<>(b.headers());
      remaining.remove("accept");
      // All observed JDK/application headers, including transport headers, must match.
      // Only header-name casing/order is normalized; no header values are removed.
      assertThat(remaining).isEqualTo(a.headers());
      for (String name :
          List.of(
              "content-type",
              "origin",
              "referer",
              "cookie",
              "x-v-requestverificationtoken",
              "x-v-appguid",
              "x-requested-with")) {
        assertThat(a.headers()).containsKey(name);
        assertThat(b.headers().get(name)).isEqualTo(a.headers().get(name));
      }
      assertThat(a.headers().get("x-v-requestverificationtoken")).containsExactly(TOKEN);
      assertThat(a.headers().get("x-v-appguid")).containsExactly(GUID);
      assertThat(a.headers())
          .doesNotContainKeys(
              "accept-language", "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void decoratorRetainsTheExactJdkFactoryClientCookiesAndConfigurationAndCannotBeReused()
      throws Exception {
    String base = "http://127.0.0.1:9/SECRET_TENANT/";
    URI endpoint = URI.create(fields(base)[0]);
    try (var input = input(fields(base))) {
      var client = new VulcanClient(input.validate(endpoint).session());
      Object adapter = ReflectionTestUtils.getField(client, "scheduleAdapter");
      Object transport = ReflectionTestUtils.getField(adapter, "transport");
      Object rest = ReflectionTestUtils.getField(transport, "restClient");
      var factory =
          (JdkClientHttpRequestFactory) ReflectionTestUtils.getField(rest, "clientRequestFactory");
      var http = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
      Object timeout = ReflectionTestUtils.getField(factory, "readTimeout");
      var cookies = http.cookieHandler();
      var version = http.version();
      var redirects = http.followRedirects();
      var connectTimeout = http.connectTimeout();
      var decorator = NativeSessionAcceptDecorator.install(client, endpoint);
      assertThat(ReflectionTestUtils.getField(decorator, "delegate")).isSameAs(factory);
      assertThat(ReflectionTestUtils.getField(factory, "httpClient")).isSameAs(http);
      assertThat(http.cookieHandler()).isEqualTo(cookies);
      assertThat(http.cookieHandler().orElseThrow()).isSameAs(cookies.orElseThrow());
      assertThat(http.version()).isEqualTo(version);
      assertThat(http.followRedirects()).isEqualTo(redirects).isEqualTo(HttpClient.Redirect.NEVER);
      assertThat(http.connectTimeout()).isEqualTo(connectTimeout);
      assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(timeout);
      assertThatThrownBy(() -> decorator.createRequest(endpoint, HttpMethod.GET))
          .hasMessage("UNEXPECTED_REQUEST");
      assertThatThrownBy(
              () -> decorator.createRequest(endpoint.resolve("GetCache"), HttpMethod.POST))
          .hasMessage("UNEXPECTED_REQUEST");
      assertThat(decorator.injected()).isFalse();
      // Creating the request does not dispatch it; there is no server on this test port.
      assertThat(decorator.createRequest(endpoint, HttpMethod.POST).getHeaders().get("Accept"))
          .containsExactly("*/*");
      assertThatThrownBy(() -> decorator.createRequest(endpoint, HttpMethod.POST))
          .hasMessage("BUDGET_EXHAUSTED");
      assertThatThrownBy(() -> NativeSessionAcceptDecorator.install(client, endpoint))
          .hasMessage("INVALID_REQUEST_FACTORY");
    }
  }

  @ParameterizedTest
  @CsvSource({
    "200,application/json,SUCCESS",
    "429,application/json,RATE_LIMITED",
    "200,text/html,UNEXPECTED_HTML",
    "302,text/html,SESSION_REDIRECT",
    "401,text/html,AUTHENTICATION_REQUIRED",
    "403,application/json,AUTHENTICATION_REQUIRED",
    "503,text/html,SERVER_ERROR",
    "404,application/json,PERMANENT_HTTP"
  })
  void acceptVariantRetainsResponseParsingAndOneRequestBudget(
      int status, String type, String outcome) throws Exception {
    var captured = new CopyOnWriteArrayList<Capture>();
    var server = server(status, type, captured);
    try (var input = input(fields(base(server)))) {
      var material = input.validate(URI.create(fields(base(server))[0]));
      var permit = new VulcanNativeSessionJavaBaseline.Permit();
      var report =
          new NativeSessionBaselineReport(NativeSessionBaselineReport.Variant.ACCEPT_STAR_STAR);
      VulcanNativeSessionJavaBaseline.execute(material, permit, report);
      assertThat(report.facts())
          .containsEntry("javaOutcome", outcome)
          .containsEntry("acceptInjected", true)
          .containsEntry("javaScheduleRequests", 1)
          .containsEntry("retries", 0);
      if (status == 429)
        assertThat(report.facts())
            .containsEntry("retryAfterPresent", true)
            .containsEntry("retryAfterSeconds", 120L);
      safe(report.json());
      var second =
          new NativeSessionBaselineReport(NativeSessionBaselineReport.Variant.ACCEPT_STAR_STAR);
      VulcanNativeSessionJavaBaseline.execute(material, permit, second);
      assertThat(second.facts())
          .containsEntry("category", "BUDGET_EXHAUSTED")
          .containsEntry("acceptInjected", false);
      assertThat(captured).hasSize(1);
      assertThat(captured.get(0).headers().get("accept")).containsExactly("*/*");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void formMismatchBlocksAcceptBeforeDispatchAndGuardRejectsSecretVariantValues() {
    String[] values = fields("http://127.0.0.1:9/SECRET_TENANT/");
    values[7] = "2026-09-05T12:00:00";
    var report =
        new NativeSessionBaselineReport(NativeSessionBaselineReport.Variant.ACCEPT_STAR_STAR);
    try (var input = input(values)) {
      VulcanNativeSessionJavaBaseline.execute(
          input.validate(URI.create(values[0])),
          new VulcanNativeSessionJavaBaseline.Permit(),
          report);
    }
    assertThat(report.facts())
        .containsEntry("category", "FORM_MISMATCH")
        .containsEntry("acceptInjected", false)
        .containsEntry("javaScheduleRequests", 0);
    assertThatThrownBy(() -> report.put("variant", TOKEN)).hasMessage("UNSAFE_OUTPUT_GUARD");
    assertThatThrownBy(() -> report.put("acceptInjected", COOKIE))
        .hasMessage("UNSAFE_OUTPUT_GUARD");
    safe(report.json());
    var untouched = new NativeSessionBaselineReport();
    untouched.put("acceptInjected", true);
    assertThatThrownBy(untouched::json).hasMessage("UNSAFE_OUTPUT_GUARD");
  }

  @Test
  void powershellVariantContractsAndExplicitOptIn() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    var tests =
        run(
            new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/tests/vulcan-native-session-java-accept-baseline.Tests.ps1"),
            new byte[0]);
    assertThat(tests.exit()).isZero();
    assertThat(tests.out())
        .contains("Synthetic Accept variant PowerShell contracts passed: 11 cases.");
    var cli =
        run(
            new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/vulcan-native-session-java-accept-baseline.ps1"),
            new byte[0]);
    assertThat(cli.exit()).isEqualTo(1);
    var report = JSON.readTree(cli.out());
    assertThat(report.path("category").asString()).isEqualTo("NOT_AUTHORIZED");
    assertThat(report.path("variant").asString()).isEqualTo("ACCEPT_STAR_STAR");
    assertThat(report.path("javaScheduleRequests").intValue()).isZero();
    assertThat(report.path("acceptInjected").booleanValue()).isFalse();
  }

  private static String base(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_TENANT/";
  }

  private static HttpServer server(int status, String type, List<Capture> captures)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            Map<String, List<String>> headers = new TreeMap<>();
            exchange
                .getRequestHeaders()
                .forEach(
                    (name, values) ->
                        headers.put(name.toLowerCase(Locale.ROOT), List.copyOf(values)));
            captures.add(
                new Capture(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI(),
                    exchange.getProtocol(),
                    exchange.getRequestBody().readAllBytes(),
                    headers));
            byte[] body =
                (type.equals("text/html")
                        ? "<html>" + TOKEN + COOKIE + GUID + "</html>"
                        : "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            if (status == 429) exchange.getResponseHeaders().set("Retry-After", "120");
            if (status == 302) exchange.getResponseHeaders().set("Location", "/must-not-follow");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    return server;
  }
}
