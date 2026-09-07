package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "sessionCapture.localBrowserTests", matches = "true")
class StructuredCookieCaptureLocalBrowserTest {
  @Test
  void actualPlaywrightCookieLookupConversionAndCapturePreserveBothPaths() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try (exchange) {
            exchange
                .getResponseHeaders()
                .add("Set-Cookie", "SUPER_SECRET_COOKIE_NAME=A; Path=/; HttpOnly");
            exchange
                .getResponseHeaders()
                .add("Set-Cookie", "SUPER_SECRET_COOKIE_NAME=B; Path=/SECRET_COOKIE_PATH/");
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, 0);
            exchange
                .getResponseBody()
                .write("<html></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_COOKIE_PATH/";
    String application = base + "PlanLekcji.mvc/GetPlanLekcjiContext";
    var external = new AtomicInteger();
    try (var playwright = Playwright.create();
        var browser = playwright.chromium().launch();
        var context =
            browser.newContext(
                new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        var page = context.newPage()) {
      context.route(
          "**/*",
          route -> {
            if (route.request().url().equals(base)) route.resume();
            else {
              external.incrementAndGet();
              route.abort();
            }
          });
      page.navigate(base);
      var observation =
          new BrowserRequestObservation(
              URI.create(application), base, "SUPER_SECRET_TOKEN", "SUPER_SECRET_APPGUID");
      var captured =
          PlaywrightVulcanBrowserAuthenticator.cookiesForObservedApplication(
              context, List.of(observation), new SessionCaptureDiagnostics());
      var validator = mock(PortalUrlValidator.class);
      when(validator.isAllowedRuntimeUri(URI.create(application))).thenReturn(true);
      var material = new VulcanSessionCapture(validator).capture(List.of(observation), captured);
      assertThat(material.cookieRepresentation())
          .isEqualTo(VulcanSessionMaterial.CookieRepresentation.STRUCTURED);
      assertThat(material.cookieCount()).isEqualTo(2);
      assertThat(material.cookies().stream().filter(VulcanCookieMaterial::httpOnly).count())
          .isEqualTo(1);
      var session = VulcanSession.fromMaterial(material);
      assertThat(SessionMaterialTestSupport.topology(session).duplicateNameDifferentPathPresent())
          .isTrue();
      assertThat(SessionMaterialTestSupport.compare(material, session.snapshotMaterial()).allSame())
          .isTrue();
      assertThat(captured.toString() + material + material.cookies())
          .doesNotContain("SUPER_SECRET", "SECRET_COOKIE_PATH", "127.0.0.1");
      assertThat(calls.get()).isEqualTo(1);
      assertThat(external.get()).isZero();
    } finally {
      server.stop(0);
    }
  }
}
