package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SmokeScriptTest {
  @Test
  void cacheFailureAllowlistExactlyMatchesTheJavaEnumOnEveryPlatform() throws Exception {
    String script = Files.readString(Path.of("scripts/vulcan-real-smoke.ps1"));
    var matcher =
        java.util.regex.Pattern.compile("\\^cacheFailure=\\(([^)]*)\\)\\$").matcher(script);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1).split("\\|"))
        .containsExactly(
            java.util.Arrays.stream(
                    io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics
                        .VulcanDiagnostics.CacheFailure.values())
                .map(Enum::name)
                .toArray(String[]::new));
    assertThat(matcher.find()).isFalse();
  }

  @Test
  void helpAndSyntheticDpapiContractsUseOnlyAnIsolatedTemporaryRoot() throws Exception {
    assumeTrue(System.getProperty("os.name").startsWith("Windows"), "DPAPI requires Windows");
    Process process =
        new ProcessBuilder(
                "pwsh.exe", "-NoProfile", "-File", "scripts/tests/vulcan-real-smoke.Tests.ps1")
            .redirectErrorStream(true)
            .start();
    assertThat(process.waitFor(45, TimeUnit.SECONDS)).isTrue();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(output)
        .contains("Synthetic PowerShell smoke contracts passed.")
        .doesNotContain(
            "synthetic-password",
            "synthetic-login",
            "synthetic private stderr",
            "https://private.example");
  }

  @Test
  void protectedBundleIsIgnoredAndNotTrackedAndScriptHasNoOtherSecretDependency() throws Exception {
    Process ignored =
        new ProcessBuilder("git", "check-ignore", ".dev/vulcan-real-smoke.dpapi").start();
    assertThat(ignored.waitFor()).isZero();
    Process tracked = new ProcessBuilder("git", "ls-files", ".dev/*").start();
    assertThat(tracked.waitFor()).isZero();
    assertThat(new String(tracked.getInputStream().readAllBytes())).isBlank();
    String script = Files.readString(Path.of("scripts/vulcan-real-smoke.ps1"));
    assertThat(script)
        .doesNotContain(
            "vulcan-master-key.dpapi",
            "telegram-bot-token.dpapi",
            "spring-boot:run",
            "install chromium");
  }
}
