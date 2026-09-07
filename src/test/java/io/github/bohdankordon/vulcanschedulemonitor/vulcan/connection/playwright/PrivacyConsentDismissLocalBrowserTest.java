package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Optional real DOM/CSS check: only an in-process loopback server, never a provider address. */
@EnabledIfSystemProperty(named = "privacyConsent.localBrowserTests", matches = "true")
class PrivacyConsentDismissLocalBrowserTest {
  @ParameterizedTest
  @CsvSource({
    "visible,OWNER_VISIBLE_INTERACTIVE,TRUE,TRUE,FALSE",
    "innerGone,OWNER_VISIBLE_INTERACTIVE,FALSE,FALSE,FALSE",
    "pointerNone,OWNER_VISIBLE_NON_INTERACTIVE,FALSE,FALSE,FALSE",
    "zeroArea,OWNER_HIDDEN,FALSE,FALSE,FALSE",
    "inert,OWNER_VISIBLE_NON_INTERACTIVE,FALSE,FALSE,FALSE",
    "ariaHidden,OWNER_VISIBLE_INTERACTIVE,FALSE,FALSE,TRUE",
    "hidden,OWNER_HIDDEN,FALSE,FALSE,FALSE",
    "detached,FRAME_DETACHED,UNAVAILABLE,UNAVAILABLE,UNAVAILABLE"
  })
  void postClickStructureNeverReplacesTheExistingDismissalRule(
      String variant,
      PrivacyConsentDismissState state,
      PrivacyConsentDismissObservation.Fact heading,
      PrivacyConsentDismissObservation.Fact container,
      PrivacyConsentDismissObservation.Fact aria)
      throws Exception {
    String mutation =
        switch (variant) {
          case "pointerNone" -> "frameElement.style.pointerEvents = 'none';";
          case "zeroArea" -> "frameElement.style.width = '0px'; frameElement.style.border = '0';";
          case "inert" -> "frameElement.inert = true;";
          case "ariaHidden" -> "frameElement.setAttribute('aria-hidden', 'true');";
          case "hidden" -> "frameElement.style.display = 'none';";
          case "detached" -> "frameElement.remove();";
          default -> "";
        };
    String html =
        "<html><meta charset='UTF-8'><body><div role='dialog' id='privacy'>"
            + "<h2>Szanujemy Twoją prywatność</h2><button onclick=\""
            + (variant.equals("visible") ? "" : "document.getElementById('privacy').remove();")
            + mutation
            + "\">Zgadzam się</button></div></body></html>";
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] content =
              (exchange.getRequestURI().getPath().equals("/privacy")
                      ? html
                      : "<html><body><iframe style='width:600px;height:400px' src='/privacy'></iframe></body></html>")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
          exchange.sendResponseHeaders(200, content.length);
          try (var body = exchange.getResponseBody()) {
            body.write(content);
          }
        });
    server.start();
    String origin = "http://127.0.0.1:" + server.getAddress().getPort();
    try (var playwright = Playwright.create();
        var browser = playwright.chromium().launch();
        var context =
            browser.newContext(
                new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        var page = context.newPage()) {
      var unexpected = new AtomicInteger();
      context.route(
          "**/*",
          route -> {
            if (route.request().url().equals(origin + "/")
                || route.request().url().equals(origin + "/privacy")) {
              route.resume();
            } else {
              unexpected.incrementAndGet();
              route.abort();
            }
          });
      // Test-only validator permits this exact local fixture origin; production policy is intact.
      var urls = mock(PortalUrlValidator.class);
      when(urls.isAllowedRuntimeUri(any()))
          .thenAnswer(
              call -> {
                URI uri = call.getArgument(0);
                return uri.toASCIIString().equals(origin + "/")
                    || uri.toASCIIString().equals(origin + "/privacy");
              });
      page.navigate(origin + "/");
      var observation = new AtomicReference<PrivacyConsentDismissObservation>();
      Runnable dismiss =
          () ->
              VulcanPrivacyConsent.dismissIfPresent(page, urls, operation -> {}, observation::set);
      boolean success =
          variant.equals("hidden") || variant.equals("detached") || variant.equals("zeroArea");
      // Chromium reports an actual zero-width owner as invisible: existing behavior succeeds.
      // The mocked visible + zero-area edge case separately proves diagnostics cannot permit it.
      if (success) assertThatCode(dismiss::run).doesNotThrowAnyException();
      else assertThatThrownBy(dismiss::run).isInstanceOf(TimeoutError.class);
      assertThat(observation.get().failure())
          .isEqualTo(
              success
                  ? PrivacyConsentDismissFailureKind.NOT_APPLICABLE
                  : PrivacyConsentDismissFailureKind.WAIT_TIMEOUT);
      assertThat(observation.get().state()).isEqualTo(state);
      assertThat(observation.get().headingPresent()).isEqualTo(heading);
      assertThat(observation.get().containerVisible()).isEqualTo(container);
      assertThat(observation.get().anyOwnerAriaHidden()).isEqualTo(aria);
      PrivacyConsentDismissDiagnosticsTest.assertFiniteLog(
          PlaywrightVulcanBrowserAuthenticator.formatFailure(
              BrowserAuthStage.INITIAL_PORTAL_CONSENT,
              PrivacyConsentOperation.DISMISS_WAIT,
              observation.get(),
              VulcanAuthFailureCategory.TRANSIENT));
      assertThat(unexpected).hasValue(0);
    } finally {
      server.stop(0);
    }
  }
}
