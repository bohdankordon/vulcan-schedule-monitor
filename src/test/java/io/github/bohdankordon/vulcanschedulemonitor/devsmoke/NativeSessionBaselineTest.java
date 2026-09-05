package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.ObjectMapper;

class NativeSessionBaselineTest {
  static final String TOKEN = "SUPER_SECRET_TOKEN",
      GUID = "SUPER_SECRET_GUID",
      COOKIE = "SUPER_SECRET_COOKIE";
  static final String BASE = "https://synthetic.vulcan.net.pl/SECRET_TENANT/";
  static final ObjectMapper JSON = new ObjectMapper();

  static String[] fields(String base) {
    return new String[] {
      base + NativeSessionBaselineInput.ENDPOINT,
      base + "Other.mvc",
      TOKEN,
      GUID,
      "CookieOne=" + COOKIE + "; CookieTwo=" + COOKIE,
      "2026-08-31T00:00:00",
      "2026-09-06T00:00:00",
      "2026-09-05T00:00:00",
      "918273645"
    };
  }

  static byte[] payload(String[] fields) {
    ByteBuffer buffer = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put(new byte[] {'N', 'S', 'J', '1'});
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      buffer.putInt(bytes.length).put(bytes);
    }
    return Arrays.copyOf(buffer.array(), buffer.position());
  }

  static NativeSessionBaselineInput input(String[] fields) {
    return NativeSessionBaselineInput.read(new ByteArrayInputStream(payload(fields)));
  }

  static Stream<Arguments> invalidMaterial() {
    return Stream.of(
        Arguments.of(
            0,
            "http://synthetic.vulcan.net.pl/SECRET_TENANT/" + NativeSessionBaselineInput.ENDPOINT),
        Arguments.of(
            0, "https://external.invalid/SECRET_TENANT/" + NativeSessionBaselineInput.ENDPOINT),
        Arguments.of(
            0,
            "https://synthetic.vulcan.net.pl:444/SECRET_TENANT/"
                + NativeSessionBaselineInput.ENDPOINT),
        Arguments.of(
            0,
            "https://private@synthetic.vulcan.net.pl/SECRET_TENANT/"
                + NativeSessionBaselineInput.ENDPOINT),
        Arguments.of(0, BASE + "GetCache"),
        Arguments.of(0, BASE + NativeSessionBaselineInput.ENDPOINT + "?" + TOKEN),
        Arguments.of(0, BASE + "../SECRET_TENANT/" + NativeSessionBaselineInput.ENDPOINT),
        Arguments.of(1, "https://external.invalid/SECRET_TENANT/"),
        Arguments.of(1, "https://synthetic.vulcan.net.pl/other/"),
        Arguments.of(2, " "),
        Arguments.of(3, " "),
        Arguments.of(2, TOKEN + "\r\nInjected: value"),
        Arguments.of(4, COOKIE),
        Arguments.of(4, "a=" + COOKIE + "; a=" + COOKIE),
        Arguments.of(4, "a=" + COOKIE + "\r\n"),
        Arguments.of(4, "bad name=" + COOKIE),
        Arguments.of(5, "2026-08-31 00:00:00"),
        Arguments.of(5, "2026-02-31T00:00:00"),
        Arguments.of(6, "2026-09-13T00:00:00"),
        Arguments.of(7, "2026-09-07T00:00:00"),
        Arguments.of(8, "0"),
        Arguments.of(8, "SECRET_JOURNAL"),
        Arguments.of(8, "9999999999999999999"));
  }

  @ParameterizedTest
  @MethodSource("invalidMaterial")
  void independentlyRejectsUnsafeStdinMaterial(int index, String value) {
    String[] values = fields(BASE);
    values[index] = value;
    try (var input = input(values)) {
      assertThatThrownBy(input::validate).hasMessage("INVALID_INPUT").hasNoCause();
      safe(input.toString());
    }
  }

  @Test
  void strictFramingAndRedactedInput() {
    for (byte[] bytes :
        List.of(
            new byte[0],
            new byte[65537],
            Arrays.copyOf(payload(fields(BASE)), 12),
            Arrays.copyOf(payload(fields(BASE)), payload(fields(BASE)).length + 1))) {
      assertThatThrownBy(() -> NativeSessionBaselineInput.read(new ByteArrayInputStream(bytes)))
          .hasMessage("INVALID_INPUT")
          .hasNoCause();
    }
    try (var input = input(fields(BASE))) {
      var material = input.validate();
      assertThat(material.cookieCount()).isEqualTo(2);
      assertThat(VulcanNativeSessionJavaBaseline.productionFormMatches(material)).isTrue();
      safe(material.toString());
      safe(input.toString());
    }
  }

  @Test
  void productionFormMismatchConsumesNoPermitAndOpensNoConnection() {
    String[] values = fields("http://127.0.0.1:9/SECRET_TENANT/");
    values[7] = "2026-09-05T12:00:00";
    try (var input = input(values)) {
      var report = new NativeSessionBaselineReport();
      var permit = new VulcanNativeSessionJavaBaseline.Permit();
      VulcanNativeSessionJavaBaseline.execute(
          input.validate(URI.create(values[0])), permit, report);
      assertThat(report.facts())
          .containsEntry("category", "FORM_MISMATCH")
          .containsEntry("javaRequestAttempted", false)
          .containsEntry("javaScheduleRequests", 0);
      safe(report.json());
      permit.take();
      assertThatThrownBy(permit::take).hasMessage("BUDGET_EXHAUSTED");
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
  void oneUntouchedLoopbackRequestNoRetriesOrRedirectFollow(int status, String type, String outcome)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try (exchange) {
            assertThat(exchange.getRequestURI().getPath())
                .endsWith("/" + NativeSessionBaselineInput.ENDPOINT);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Accept")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Accept-Language")).isNull();
            assertThat(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site")).isNull();
            exchange.getRequestBody().readAllBytes();
            byte[] response =
                (type.equals("text/html")
                        ? "<html>" + TOKEN + "</html>"
                        : "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            if (status == 429) exchange.getResponseHeaders().set("Retry-After", "120");
            if (status == 302) exchange.getResponseHeaders().set("Location", "/must-not-follow");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
          }
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_TENANT/";
      try (var input = input(fields(base))) {
        var material = input.validate(URI.create(base + NativeSessionBaselineInput.ENDPOINT));
        var permit = new VulcanNativeSessionJavaBaseline.Permit();
        var report = new NativeSessionBaselineReport();
        VulcanNativeSessionJavaBaseline.execute(material, permit, report);
        assertThat(report.facts())
            .containsEntry("javaOutcome", outcome)
            .containsEntry("javaScheduleRequests", 1)
            .containsEntry("retries", 0);
        if (status == 429)
          assertThat(report.facts())
              .containsEntry("retryAfterPresent", true)
              .containsEntry("retryAfterSeconds", 120L);
        if (status == 200 && type.equals("text/html"))
          assertThat(report.facts())
              .containsEntry("java.contentFamily", "html")
              .containsEntry("java.statusFamily", "2xx");
        safe(report.json());
        var second = new NativeSessionBaselineReport();
        VulcanNativeSessionJavaBaseline.runOnce(
            permit,
            second,
            () -> {
              throw new AssertionError("Must not execute");
            });
        assertThat(second.facts()).containsEntry("category", "BUDGET_EXHAUSTED");
        assertThat(calls.get()).isEqualTo(1);
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void finiteErrorsCannotLeakRawExceptionTextOrUnknownKeys() {
    var report = new NativeSessionBaselineReport();
    VulcanNativeSessionJavaBaseline.runOnce(
        new VulcanNativeSessionJavaBaseline.Permit(),
        report,
        () -> {
          throw new IllegalStateException(TOKEN + GUID + COOKIE);
        });
    assertThat(report.facts())
        .containsEntry("category", "HARNESS_FAILURE")
        .containsEntry("javaScheduleRequests", 1);
    safe(report.json());
    assertThatThrownBy(() -> report.put("unknown", TOKEN)).hasMessage("UNSAFE_OUTPUT_GUARD");
    assertThatThrownBy(() -> report.put("javaOutcome", TOKEN)).hasMessage("UNSAFE_OUTPUT_GUARD");
    var transport = new NativeSessionBaselineReport();
    VulcanNativeSessionJavaBaseline.runOnce(
        new VulcanNativeSessionJavaBaseline.Permit(),
        transport,
        () -> {
          throw io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException
              .transportFailure("GetPlanLekcjiContext");
        });
    assertThat(transport.facts()).containsEntry("javaOutcome", "TRANSPORT_ERROR");
    var protocol = new NativeSessionBaselineReport();
    VulcanNativeSessionJavaBaseline.runOnce(
        new VulcanNativeSessionJavaBaseline.Permit(),
        protocol,
        () -> {
          throw new io.github.bohdankordon.vulcanschedulemonitor.vulcan.http
              .VulcanProtocolException("GetPlanLekcjiContext");
        });
    assertThat(protocol.facts()).containsEntry("javaOutcome", "PROTOCOL_FAILURE");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ABSENT", "DATE", "MALFORMED", "HUGE"})
  void retryAfterIsDurationOnlyWithNoRetry(String mode) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try (exchange) {
            exchange.getRequestBody().readAllBytes();
            String header =
                switch (mode) {
                  case "DATE" ->
                      java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                          .plusSeconds(120)
                          .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
                  case "MALFORMED" -> TOKEN;
                  case "HUGE" -> "999999999";
                  default -> null;
                };
            if (header != null) exchange.getResponseHeaders().set("Retry-After", header);
            exchange.sendResponseHeaders(429, -1);
          }
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_TENANT/";
      try (var input = input(fields(base))) {
        var report = new NativeSessionBaselineReport();
        VulcanNativeSessionJavaBaseline.execute(
            input.validate(URI.create(base + NativeSessionBaselineInput.ENDPOINT)),
            new VulcanNativeSessionJavaBaseline.Permit(),
            report);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(report.facts())
            .containsEntry("javaOutcome", "RATE_LIMITED")
            .containsEntry("retries", 0);
        if (mode.equals("DATE"))
          assertThat((Long) report.facts().get("retryAfterSeconds")).isBetween(0L, 120L);
        if (mode.equals("HUGE"))
          assertThat(report.facts()).containsEntry("retryAfterSeconds", 31536000L);
        if (Set.of("ABSENT", "MALFORMED").contains(mode))
          assertThat(report.facts())
              .containsEntry("retryAfterPresent", false)
              .containsEntry("retryAfterSeconds", "UNAVAILABLE");
        safe(report.json());
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void scriptSyntheticContractsAndOptInCli() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    var tests =
        run(
            new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-File",
                "scripts/tests/vulcan-native-session-java-baseline.Tests.ps1"),
            new byte[0]);
    assertThat(tests.exit()).isZero();
    assertThat(tests.out())
        .contains("Synthetic native-session PowerShell contracts passed: 37 cases.");
    var cli =
        run(
            new ProcessBuilder(
                "pwsh", "-NoProfile", "-File", "scripts/vulcan-native-session-java-baseline.ps1"),
            new byte[0]);
    assertThat(cli.exit()).isEqualTo(1);
    assertThat(JSON.readTree(cli.out()).path("category").asString()).isEqualTo("NOT_AUTHORIZED");
    var denied = run(javaBuilder(), payload(fields(BASE)));
    assertThat(denied.exit()).isEqualTo(1);
    assertThat(JSON.readTree(denied.out()).path("category").asString()).isEqualTo("NOT_AUTHORIZED");
  }

  @ParameterizedTest
  @CsvSource({
    "false,UNTOUCHED",
    "true,UNTOUCHED",
    "false,ACCEPT_STAR_STAR",
    "true,ACCEPT_STAR_STAR"
  })
  void actualStdinBoundaryNeverPermitsProviderTraffic(boolean matchingForm, String variant)
      throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    String command =
        ". ./scripts/vulcan-native-session-java-baseline.ps1; "
            + "$p = Get-NativeSessionPayload ([Console]::In.ReadToEnd()); "
            + "try { $r = Invoke-NativeBaselineChild $env:NATIVE_TEST_CLASSPATH $p $true -Variant $env:NATIVE_TEST_VARIANT; ConvertTo-Json -InputObject $r -Depth 4 } "
            + "finally { [Array]::Clear($p, 0, $p.Length) }";
    var process = new ProcessBuilder("pwsh", "-NoProfile", "-Command", command);
    process.environment().put("NATIVE_TEST_CLASSPATH", classpath());
    process.environment().put("NATIVE_TEST_VARIANT", variant);
    String har = matchingForm ? fixtureHar().replace("T12%3A", "T00%3A") : fixtureHar();
    var response = run(process, har.getBytes(StandardCharsets.UTF_8));
    assertThat(response.exit()).isZero();
    var report = JSON.readTree(response.out());
    assertThat(report.path("category").asString())
        .isEqualTo(matchingForm ? "VALIDATION_ONLY" : "FORM_MISMATCH");
    assertThat(report.path("javaScheduleRequests").intValue()).isZero();
    assertThat(report.path("nativeEvidenceValidated").booleanValue()).isTrue();
    assertThat(report.path("variant").asString()).isEqualTo(variant);
    assertThat(report.path("acceptInjected").booleanValue()).isFalse();
  }

  private static String fixtureHar() {
    var headers =
        List.of(
            Map.of("name", "Referer", "value", BASE + "Other.mvc"),
            Map.of("name", "X-V-RequestVerificationToken", "value", TOKEN),
            Map.of("name", "X-V-AppGuid", "value", GUID),
            Map.of("name", "Cookie", "value", "One=" + COOKIE),
            Map.of("name", "Content-Type", "value", "application/x-www-form-urlencoded"));
    var request =
        Map.of(
            "method",
            "POST",
            "url",
            BASE + NativeSessionBaselineInput.ENDPOINT,
            "headers",
            headers,
            "postData",
            Map.of(
                "mimeType",
                "application/x-www-form-urlencoded",
                "text",
                "dataOd=2026-08-31T00%3A00%3A00&dataDo=2026-09-06T00%3A00%3A00&data=2026-09-05T12%3A00%3A00&idDziennik=918273645"));
    var response =
        Map.of(
            "status",
            200,
            "content",
            Map.of(
                "mimeType",
                "application/json",
                "text",
                "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}"));
    return JSON.writeValueAsString(
        Map.of(
            "log", Map.of("entries", List.of(Map.of("request", request, "response", response)))));
  }

  private static String classpath() {
    return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
  }

  private static ProcessBuilder javaBuilder() {
    return new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp",
        classpath(),
        VulcanNativeSessionJavaBaseline.class.getName());
  }

  record Result(String out, int exit) {}

  static Result run(ProcessBuilder builder, byte[] input) throws Exception {
    var process = builder.start();
    var output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
    var errors = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
    process.getOutputStream().write(input);
    process.getOutputStream().close();
    boolean done = process.waitFor(50, TimeUnit.SECONDS);
    if (!done) process.destroyForcibly();
    assertThat(done).isTrue();
    String out = output.get(5, TimeUnit.SECONDS), err = errors.get(5, TimeUnit.SECONDS);
    safe(out);
    safe(err);
    assertThat(err).isEmpty();
    return new Result(out, process.exitValue());
  }

  private static String read(InputStream stream) {
    try {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void safe(String value) {
    assertThat(value)
        .doesNotContain(
            TOKEN,
            GUID,
            COOKIE,
            "SECRET_TENANT",
            "SECRET_JOURNAL",
            "918273645",
            "https://",
            "http://");
  }
}
