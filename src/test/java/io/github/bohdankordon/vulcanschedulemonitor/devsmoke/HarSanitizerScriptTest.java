package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class HarSanitizerScriptTest {
  @Test
  void syntheticOfflinePowerShellContracts() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"), "PowerShell runner on Windows");
    Process process =
        new ProcessBuilder(
                "pwsh.exe",
                "-NoProfile",
                "-File",
                "scripts/tests/sanitize-vulcan-schedule-har.Tests.ps1")
            .redirectErrorStream(true)
            .start();
    var output =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return process.getInputStream().readAllBytes();
              } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
              }
            });
    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) process.destroyForcibly();
    assertThat(finished).isTrue();
    String text = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(text).isZero();
    assertThat(text)
        .contains("Synthetic offline HAR contracts passed:")
        .doesNotContain("SUPER_SECRET_", "https://", "SCHOOL_IDENTIFIER_123", "CLASS_NAME_SECRET");
  }

  @Test
  void capturesAreIgnoredAndNoDevFilesAreTracked() throws Exception {
    for (String capture :
        new String[] {
          ".dev/vulcan-schedule-native.har", ".dev/vulcan-schedule-native.sanitized.json"
        }) {
      Process process = new ProcessBuilder("git", "check-ignore", capture).start();
      assertThat(process.waitFor()).isZero();
    }
    Process tracked = new ProcessBuilder("git", "ls-files", ".dev/*").start();
    assertThat(tracked.waitFor()).isZero();
    assertThat(new String(tracked.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .isBlank();
    String script = Files.readString(Path.of("scripts/sanitize-vulcan-schedule-har.ps1"));
    assertThat(script)
        .doesNotContain(
            "Invoke-WebRequest",
            "Invoke-RestMethod",
            "HttpClient",
            "WebClient",
            "Start-Process",
            "Playwright",
            "vulcan-real-smoke.dpapi",
            "mvnw",
            "spring-boot",
            "Resolve-DnsName");
  }
}
