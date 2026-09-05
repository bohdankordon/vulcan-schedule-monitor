package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.Stage;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class VulcanRealSmokeTest {
  private static final String PORTAL = "https://school.vulcan.net.pl/synthetic/?private=value";
  private final String monitoringBefore = System.getProperty("vulcan.monitoring.enabled");
  private final String telegramBefore = System.getProperty("telegram.bot.enabled");

  @AfterEach
  void restoreProperties() {
    restore("vulcan.monitoring.enabled", monitoringBefore);
    restore("telegram.bot.enabled", telegramBefore);
  }

  private void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }

  @ParameterizedTest
  @EnumSource(value = VulcanAuthFailureCategory.class)
  void anyBrowserFailureStopsAfterOneAttemptWithoutVerificationOrSecretOutput(
      VulcanAuthFailureCategory category) throws Exception {
    var diagnostics = new SmokeDiagnostics();
    var browser = mock(VulcanBrowserAuthenticator.class);
    var verifier = mock(VulcanSessionVerifier.class);
    when(browser.authenticate(any())).thenThrow(new VulcanAuthenticationException(category));
    try (SmokeInput input = SmokeInput.read(new ByteArrayInputStream(payload(PORTAL)))) {
      assertThat(VulcanRealSmoke.attempt(input, diagnostics, browser, verifier)).isEqualTo(1);
    }
    verify(browser).authenticate(any());
    verifyNoInteractions(verifier);
    String report = report(diagnostics);
    assertThat(report)
        .contains("stage.BROWSER_AUTH=FAIL", "category=" + category, "result=FAIL")
        .doesNotContain(PORTAL, "synthetic-login", "synthetic-password");
    assertThat(System.getProperty("vulcan.monitoring.enabled")).isEqualTo("false");
    assertThat(System.getProperty("telegram.bot.enabled")).isEqualTo("false");
  }

  @Test
  void successfulAttemptReusesOneBrowserAndVerifierWithoutRetainingThePassword() throws Exception {
    var diagnostics = new SmokeDiagnostics();
    var browser = mock(VulcanBrowserAuthenticator.class);
    var verifier = mock(VulcanSessionVerifier.class);
    URI app = URI.create("https://school.vulcan.net.pl/synthetic/");
    var material =
        new VulcanSessionMaterial(
            app, app, "private-token", "private-guid", "private-cookie=value");
    when(browser.authenticate(any())).thenReturn(material);
    when(verifier.verifyAndDiscover(material))
        .thenReturn(new VerifiedVulcanSession(material, List.of()));
    SmokeInput input = SmokeInput.read(new ByteArrayInputStream(payload(PORTAL)));
    try (input) {
      assertThat(VulcanRealSmoke.attempt(input, diagnostics, browser, verifier)).isZero();
    }
    assertThat(input.password).containsOnly('\0');
    var request = org.mockito.ArgumentCaptor.forClass(VulcanLoginRequest.class);
    verify(browser).authenticate(request.capture());
    assertThat(request.getValue().password()).containsOnly('\0');
    verify(verifier).verifyAndDiscover(material);
    assertThat(report(diagnostics))
        .contains("classCount=0", "result=SUCCESS")
        .doesNotContain("private-cookie", "private-guid", "private-token");
  }

  @Test
  void invalidPortalStopsBeforeAnyNetworkComponent() throws Exception {
    var browser = mock(VulcanBrowserAuthenticator.class);
    var verifier = mock(VulcanSessionVerifier.class);
    var diagnostics = new SmokeDiagnostics();
    try (SmokeInput input =
        SmokeInput.read(new ByteArrayInputStream(payload("https://external.example/private")))) {
      assertThat(VulcanRealSmoke.attempt(input, diagnostics, browser, verifier)).isNotZero();
    }
    verifyNoInteractions(browser, verifier);
    assertThat(report(diagnostics))
        .contains("stage.PORTAL_VALIDATION=FAIL", "category=INVALID_INPUT");
  }

  @Test
  void rawRuntimeMessageNeverReachesReportAndCaptureHasItsOwnStage() throws Exception {
    var diagnostics = new SmokeDiagnostics();
    var browser = mock(VulcanBrowserAuthenticator.class);
    var verifier = mock(VulcanSessionVerifier.class);
    when(browser.authenticate(any()))
        .thenAnswer(
            invocation -> {
              diagnostics.pass(Stage.BROWSER_AUTH);
              diagnostics.begin(Stage.SESSION_CAPTURE);
              throw new RuntimeException(
                  PORTAL + " synthetic-password private-token <html>private body</html>");
            });
    try (SmokeInput input = SmokeInput.read(new ByteArrayInputStream(payload(PORTAL)))) {
      assertThat(VulcanRealSmoke.attempt(input, diagnostics, browser, verifier)).isNotZero();
    }
    assertThat(report(diagnostics))
        .contains(
            "stage.BROWSER_AUTH=PASS", "stage.SESSION_CAPTURE=FAIL", "category=PROTOCOL_FAILURE")
        .doesNotContain(PORTAL, "synthetic-password", "private-token", "<html>", "private body");
  }

  @Test
  void versionedPayloadRoundTripHandlesUtf8AndRedactsItsObjectRepresentation() throws Exception {
    try (SmokeInput input = SmokeInput.read(new ByteArrayInputStream(payload(PORTAL)))) {
      assertThat(input.portal).isEqualTo(PORTAL);
      assertThat(input.login).isEqualTo("synthetic-login");
      assertThat(new String(input.password)).isEqualTo("synthetic-password-ą-😀");
      assertThat(input.toString()).isEqualTo("SmokeInput[redacted]");
    }
  }

  @Test
  void diagnosticMainRequiresExplicitOptInAndDoesNotReadCredentialsWithoutIt() throws Exception {
    Process process =
        new ProcessBuilder(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                VulcanRealSmoke.class.getName())
            .redirectErrorStream(true)
            .start();
    try {
      process.getOutputStream().close();
      assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isEqualTo(2);
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(output)
          .contains("category=INVALID_INPUT", "stage.BROWSER_AUTH=NOT_REACHED", "result=FAIL")
          .doesNotContain("Exception", "synthetic-password");
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 3, 7, 32769})
  void malformedOrOversizedInputIsRejectedBeforeConstructingAConnection(int size) {
    assertThatThrownBy(() -> SmokeInput.read(new ByteArrayInputStream(new byte[size])))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid smoke input");
  }

  @Test
  void unknownVersionTrailingBytesInvalidUtf8AndInvalidLengthsAreRejected() {
    byte[] valid = payload(PORTAL);
    byte[] wrongVersion = valid.clone();
    wrongVersion[3] = '2';
    byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
    byte[] invalidUtf8 = valid.clone();
    invalidUtf8[8] = (byte) 0xff;
    byte[] badLength = valid.clone();
    ByteBuffer.wrap(badLength).order(ByteOrder.LITTLE_ENDIAN).putInt(4, -1);
    for (byte[] invalid : List.of(wrongVersion, trailing, invalidUtf8, badLength)) {
      assertThatThrownBy(() -> SmokeInput.read(new ByteArrayInputStream(invalid)))
          .isInstanceOfAny(
              IllegalArgumentException.class, java.nio.charset.CharacterCodingException.class);
    }
  }

  static byte[] payload(String portal) {
    ByteBuffer buffer = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put("VSM1".getBytes(StandardCharsets.US_ASCII));
    for (String value : List.of(portal, "synthetic-login", "synthetic-password-ą-😀")) {
      byte[] field = value.getBytes(StandardCharsets.UTF_8);
      buffer.putInt(field.length).put(field);
    }
    return java.util.Arrays.copyOf(buffer.array(), buffer.position());
  }

  static String report(SmokeDiagnostics diagnostics) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    diagnostics.print(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
