package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

class Schedule429FingerprintTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String SECRET = "SUPER_SECRET_TOKEN";

  static Stream<Arguments> categories() {
    return Stream.of(
        Arguments.of(
            "User-Agent",
            "Mozilla/5.0 Chrome/123.0.0.0 Safari/537.36",
            "userAgent.family",
            "CHROMIUM_BROWSER"),
        Arguments.of(
            "User-Agent", "Java-http-client/21.0.1", "userAgent.family", "JAVA_HTTP_CLIENT"),
        Arguments.of(
            "User-Agent", "Mozilla/5.0 Firefox/125.0", "userAgent.family", "FIREFOX_BROWSER"),
        Arguments.of(
            "User-Agent",
            "Mozilla/5.0 Version/17.0 Safari/605.1",
            "userAgent.family",
            "SAFARI_BROWSER"),
        Arguments.of("User-Agent", SECRET, "userAgent.family", "OTHER"),
        Arguments.of("User-Agent", "", "userAgent.family", "OTHER"),
        Arguments.of("Sec-CH-UA-Mobile", "?0", "secChUaMobile.category", "NOT_MOBILE"),
        Arguments.of("Sec-CH-UA-Mobile", "?1", "secChUaMobile.category", "MOBILE"),
        Arguments.of("Sec-CH-UA-Mobile", SECRET, "secChUaMobile.category", "OTHER"),
        Arguments.of("Sec-CH-UA-Platform", "\"Windows\"", "secChUaPlatform.category", "WINDOWS"),
        Arguments.of("Sec-CH-UA-Platform", "\"macOS\"", "secChUaPlatform.category", "MACOS"),
        Arguments.of("Sec-CH-UA-Platform", "\"Linux\"", "secChUaPlatform.category", "LINUX"),
        Arguments.of("Sec-CH-UA-Platform", "\"Android\"", "secChUaPlatform.category", "ANDROID"),
        Arguments.of("Sec-CH-UA-Platform", "\"iOS\"", "secChUaPlatform.category", "IOS"),
        Arguments.of(
            "Sec-CH-UA-Platform",
            "\"Windows " + SECRET + "\"",
            "secChUaPlatform.category",
            "OTHER"),
        Arguments.of("Accept", "*/*", "accept.profile", "STAR_STAR_ONLY"),
        Arguments.of("Accept", "application/json", "accept.profile", "JSON_EXPLICIT"),
        Arguments.of(
            "Accept",
            "application/json, text/javascript, */*; q=0.01",
            "accept.profile",
            "JQUERY_JSON"),
        Arguments.of(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "accept.profile",
            "HTML_NAVIGATION"),
        Arguments.of("Accept", "application/json," + SECRET, "accept.profile", "OTHER"),
        Arguments.of("Accept", "*/*;q=0.8", "accept.profile", "OTHER"),
        Arguments.of("Accept-Language", "en-US,en;q=0.9", "acceptLanguage.family", "EN"),
        Arguments.of("Accept-Language", "pl-PL", "acceptLanguage.family", "PL"),
        Arguments.of("Accept-Language", "uk-UA,pl;q=0.5", "acceptLanguage.family", "UK"),
        Arguments.of("Accept-Language", "ru-RU", "acceptLanguage.family", "RU"),
        Arguments.of("Accept-Language", "fr-FR", "acceptLanguage.family", "OTHER"),
        Arguments.of("Accept-Language", "pl-PL " + SECRET, "acceptLanguage.family", "OTHER"),
        Arguments.of("Sec-Fetch-Dest", "empty", "secFetchDest.category", "empty"),
        Arguments.of("Sec-Fetch-Dest", "document", "secFetchDest.category", "document"),
        Arguments.of("Sec-Fetch-Dest", "iframe", "secFetchDest.category", "iframe"),
        Arguments.of("Sec-Fetch-Dest", "script", "secFetchDest.category", "script"),
        Arguments.of("Sec-Fetch-Dest", "style", "secFetchDest.category", "style"),
        Arguments.of("Sec-Fetch-Dest", "image", "secFetchDest.category", "image"),
        Arguments.of("Sec-Fetch-Dest", SECRET, "secFetchDest.category", "other"));
  }

  @ParameterizedTest
  @MethodSource("categories")
  void finiteCategories(String header, String value, String field, String expected) {
    var facts = Schedule429Fingerprint.headers(Map.of(header, value));
    assertThat(facts.get("headers." + field)).isEqualTo(expected);
    assertSafe(JSON.writeValueAsString(facts));
  }

  @Test
  void absenceAndPresenceOnlyHeadersAndEncodingFlags() {
    var absent = Schedule429Fingerprint.headers(Map.of());
    assertThat(absent)
        .containsEntry("headers.userAgent.family", "ABSENT")
        .containsEntry("headers.accept.profile", "ABSENT")
        .containsEntry("headers.acceptLanguage.family", "ABSENT")
        .containsEntry("headers.acceptLanguage.multipleLanguages", false);
    var present =
        Schedule429Fingerprint.headers(
            Map.of(
                "Sec-CH-UA",
                SECRET,
                "Priority",
                SECRET,
                "Cache-Control",
                SECRET,
                "Pragma",
                SECRET,
                "DNT",
                SECRET,
                "Accept-Encoding",
                "gzip, deflate, br, zstd;q=0, " + SECRET,
                "Accept-Language",
                "pl-PL,en;q=0.9",
                "Unknown-" + SECRET,
                SECRET));
    for (String key : List.of("secChUa", "priority", "cacheControl", "pragma", "dnt")) {
      assertThat(present).containsEntry("headers." + key + ".present", true);
    }
    for (String key : List.of("gzip", "deflate", "br", "zstd", "other")) {
      assertThat(present).containsEntry("headers.acceptEncoding." + key, true);
    }
    assertThat(present)
        .containsEntry("requestHeaderCount", 8)
        .containsEntry("headers.acceptLanguage.multipleLanguages", true);
    assertSafe(JSON.writeValueAsString(present));
  }

  @Test
  void untouchedProductionClientMeasuresActualAndPreferredVersionsSeparately() throws Exception {
    var shape = Schedule429JavaShape.measure();
    var profile = shape.fingerprint();
    assertThat(profile)
        .containsEntry("schemaVersion", 2)
        .containsEntry("profileSource", "JAVA_LOOPBACK")
        .containsEntry("actualObservedHttpVersion", "HTTP_1_1")
        .containsEntry("clientPreferredVersion", "HTTP_2")
        .containsEntry("headers.userAgent.family", "JAVA_HTTP_CLIENT")
        .containsEntry("headers.accept.present", false)
        .containsEntry("headers.acceptLanguage.present", false)
        .containsEntry("headers.secChUa.present", false)
        .containsEntry("form.exactExpectedFieldSet", true)
        .containsEntry("form.dataWithinWeek", true)
        .containsEntry("form.weekIsMondayToSunday", true);
    assertSafe(JSON.writeValueAsString(profile));
    var bad = new HashMap<>(profile);
    bad.put("headers.userAgent.family", SECRET);
    assertThatThrownBy(() -> Schedule429Fingerprint.validateProjection(bad))
        .hasMessage("UNSAFE_OUTPUT_GUARD");
    bad.put("headers.userAgent.family", "JAVA_HTTP_CLIENT");
    bad.put("unknown", SECRET);
    assertThatThrownBy(() -> Schedule429Fingerprint.validateProjection(bad))
        .hasMessage("UNSAFE_OUTPUT_GUARD");
  }

  @Test
  void powershellAndJavaClassifiersAgreeOnEverySyntheticCategory() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    List<Map<String, String>> inputs = new ArrayList<>();
    categories()
        .forEach(
            arguments -> {
              Object[] values = arguments.get();
              inputs.add(Map.of((String) values[0], (String) values[1]));
            });
    inputs.add(Map.of());
    inputs.add(
        Map.of(
            "Accept-Encoding",
            "gzip, deflate, br, zstd;q=0, " + SECRET,
            "Sec-CH-UA",
            SECRET,
            "Priority",
            SECRET,
            "Unknown-" + SECRET,
            SECRET));
    var process =
        new ProcessBuilder(
                "pwsh",
                "-NoProfile",
                "-Command",
                ". ./scripts/sanitize-vulcan-schedule-har.ps1; "
                    + "$inputs = ConvertFrom-Json -AsHashtable -NoEnumerate -InputObject ([Console]::In.ReadToEnd()); "
                    + "$safe = @($inputs | ForEach-Object { Get-FingerprintFacts $_ }); "
                    + "ConvertTo-Json -InputObject $safe -Depth 4 -Compress")
            .start();
    process.getOutputStream().write(JSON.writeValueAsBytes(inputs));
    process.getOutputStream().close();
    var output =
        java.util.concurrent.CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
    var error =
        java.util.concurrent.CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
    boolean done = process.waitFor(20, TimeUnit.SECONDS);
    if (!done) process.destroyForcibly();
    assertThat(done).isTrue();
    String stdout = output.get(5, TimeUnit.SECONDS);
    assertThat(error.get(5, TimeUnit.SECONDS)).isEmpty();
    assertThat(process.exitValue()).isZero();
    assertSafe(stdout);
    var expected = inputs.stream().map(Schedule429Fingerprint::headers).toList();
    assertThat(JSON.readTree(stdout)).isEqualTo(JSON.valueToTree(expected));
  }

  @Test
  void emittedJavaProfileIsAcceptedByOfflineComparison(@TempDir Path temporary) throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"));
    Path profile = temporary.resolve("synthetic-java.json");
    Files.writeString(
        profile, JSON.writeValueAsString(Schedule429JavaShape.measure().fingerprint()));
    String command =
        ". ./scripts/sanitize-vulcan-schedule-har.ps1; "
            + "$p = ConvertFrom-SafeProfileJson ([Console]::In.ReadToEnd()); "
            + "Assert-ProfileFields $p (Get-JavaFingerprintSchema); 'SAFE_PROFILE_ACCEPTED'";
    var process = new ProcessBuilder("pwsh", "-NoProfile", "-Command", command).start();
    process.getOutputStream().write(Files.readAllBytes(profile));
    process.getOutputStream().close();
    assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
    assertThat(read(process.getErrorStream())).isEmpty();
    assertThat(process.exitValue()).isZero();
    assertThat(read(process.getInputStream()).trim()).isEqualTo("SAFE_PROFILE_ACCEPTED");
  }

  private static String read(java.io.InputStream input) {
    try {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  @Test
  void profilerCliEmitsOnlyGuardedJsonAndFiniteErrors() throws Exception {
    String launcher = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    for (boolean invalid : List.of(false, true)) {
      List<String> command =
          new ArrayList<>(
              List.of(launcher, "-cp", classpath, Schedule429JavaShape.class.getName()));
      if (invalid) command.add(SECRET);
      Process process = new ProcessBuilder(command).start();
      var out =
          java.util.concurrent.CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
      var err =
          java.util.concurrent.CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
      boolean finished = process.waitFor(30, TimeUnit.SECONDS);
      if (!finished) process.destroyForcibly();
      assertThat(finished).isTrue();
      String stdout = out.get(5, TimeUnit.SECONDS);
      assertSafe(stdout);
      assertThat(err.get(5, TimeUnit.SECONDS)).isEmpty();
      assertThat(process.exitValue()).isEqualTo(invalid ? 1 : 0);
      var root = JSON.readTree(stdout);
      assertThat(root.path("schemaVersion").intValue()).isEqualTo(2);
      if (invalid) assertThat(root.path("result").asString()).isEqualTo("PROFILE_FAILURE");
      else
        assertThat(root.path("headers.userAgent.family").asString()).isEqualTo("JAVA_HTTP_CLIENT");
    }
  }

  private static void assertSafe(String output) {
    assertThat(output)
        .doesNotContain(
            SECRET,
            "synthetic-token",
            "synthetic-guid",
            "SyntheticCookie",
            "Chrome/123",
            "Java-http-client/21",
            "pl-PL",
            "en-US",
            "http://",
            "https://",
            "Unknown-",
            "Sec-CH-UA");
  }
}
