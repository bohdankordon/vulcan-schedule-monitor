package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright.PlaywrightVulcanBrowserAuthenticator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.Stage;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import org.slf4j.LoggerFactory;

/** Explicit local main, excluded from the production jar. Never discovered as a Maven test. */
public final class VulcanRealSmoke {
  private VulcanRealSmoke() {}

  public static void main(String[] args) {
    PrintStream report = System.out;
    // Third-party output is discarded before reading any input or constructing real components.
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    int exit = 2;
    SmokeDiagnostics diagnostics = new SmokeDiagnostics();
    try {
      LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
      logging.reset();
      logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
      java.util.logging.LogManager.getLogManager().reset();
      if (args.length != 1 || !args[0].equals("--authorized-local-smoke")) {
        diagnostics.failed(SmokeDiagnostics.Category.INVALID_INPUT);
      } else {
        disableBackgroundServices();
        try (SmokeInput input = SmokeInput.read(System.in)) {
          exit =
              attempt(
                  input,
                  diagnostics,
                  new PlaywrightVulcanBrowserAuthenticator(
                      new PortalUrlValidator(), true, diagnostics),
                  new DefaultVulcanSessionVerifier(diagnostics));
        } catch (java.io.IOException | IllegalArgumentException invalid) {
          diagnostics.failed(SmokeDiagnostics.Category.INVALID_INPUT);
        }
      }
    } catch (Throwable failure) {
      // Last-resort process boundary: never expose startup/native/browser exception details.
      diagnostics.failed(SmokeDiagnostics.Category.HARNESS_FAILURE);
      exit = 2;
    }
    diagnostics.print(report);
    System.exit(exit);
  }

  static int attempt(
      SmokeInput input,
      SmokeDiagnostics diagnostics,
      VulcanBrowserAuthenticator browser,
      VulcanSessionVerifier verifier) {
    disableBackgroundServices();
    try {
      URI portal =
          diagnostics.observe(
              Stage.PORTAL_VALIDATION, () -> new PortalUrlValidator().validate(input.portal));
      try (VulcanLoginRequest request =
          new VulcanLoginRequest(portal, input.login, input.password)) {
        diagnostics.begin(Stage.BROWSER_AUTH);
        var captured =
            browser.authenticate(request); // Exactly one attempt; no retry or Spring context.
        var verified = verifier.verifyAndDiscover(captured);
        diagnostics.success(verified.classes().size());
        return 0;
      }
    } catch (VulcanAuthenticationException failure) {
      diagnostics.failed(failure.category());
    } catch (IllegalArgumentException invalid) {
      diagnostics.failed(SmokeDiagnostics.Category.INVALID_INPUT);
    } catch (RuntimeException failure) {
      diagnostics.failed(SmokeDiagnostics.Category.PROTOCOL_FAILURE);
    }
    return 1;
  }

  static void disableBackgroundServices() {
    System.setProperty("vulcan.monitoring.enabled", "false");
    System.setProperty("telegram.bot.enabled", "false");
    // No Spring context, scheduler, database, token controller, recovery, or Telegram is started.
    // This driver never invokes credential persistence or account completion.
  }
}
