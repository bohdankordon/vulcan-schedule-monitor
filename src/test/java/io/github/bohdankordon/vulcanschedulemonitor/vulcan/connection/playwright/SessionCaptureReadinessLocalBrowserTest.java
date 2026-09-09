package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionMaterialTestSupport.cookiePairs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanLoginRequest;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "sessionCapture.localBrowserTests", matches = "true")
class SessionCaptureReadinessLocalBrowserTest {

  private static final int ARTIFICIAL_DELAY_MS = 2600;

  @Test
  void realBrowserReceivesDelayedRequestBeyondOldTwoSecondWindowAndCapturesSession()
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var submissionReceivedAt = new AtomicLong();
    var delayedRequestReceivedAt = new AtomicLong();

    server.createContext(
        "/synthetic/login",
        exchange -> {
          try (exchange) {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
              submissionReceivedAt.set(System.currentTimeMillis());
              exchange
                  .getResponseHeaders()
                  .add("Set-Cookie", "SyntheticCookie=synthetic-cookie-value; Path=/; HttpOnly");
              exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
              exchange.sendResponseHeaders(200, 0);
              String dashboardHtml =
                  """
                  <!DOCTYPE html>
                  <html>
                    <head><title>Dashboard</title></head>
                    <body>
                      <h1>Dashboard</h1>
                      <script>
                        setTimeout(() => {
                          fetch('/synthetic/Dziennik.mvc/GetTree', {
                            headers: {
                              'X-V-RequestVerificationToken': 'synthetic-token-42',
                              'X-V-AppGuid': 'synthetic-guid-42'
                            }
                          });
                        }, %d);
                      </script>
                    </body>
                  </html>
                  """
                      .formatted(ARTIFICIAL_DELAY_MS);
              try (OutputStream os = exchange.getResponseBody()) {
                os.write(dashboardHtml.getBytes(StandardCharsets.UTF_8));
              }
            } else {
              exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
              exchange.sendResponseHeaders(200, 0);
              String loginHtml =
                  """
                  <!DOCTYPE html>
                  <html>
                    <head><title>Login</title></head>
                    <body>
                      <form method="POST" action="/synthetic/login">
                        <input type="text" name="LoginName" autocomplete="username" value="" />
                        <input type="password" autocomplete="current-password" value="" />
                        <button type="submit">Log in</button>
                      </form>
                    </body>
                  </html>
                  """;
              try (OutputStream os = exchange.getResponseBody()) {
                os.write(loginHtml.getBytes(StandardCharsets.UTF_8));
              }
            }
          }
        });

    server.createContext(
        "/synthetic/Dziennik.mvc/GetTree",
        exchange -> {
          try (exchange) {
            delayedRequestReceivedAt.set(System.currentTimeMillis());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write("{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
            }
          }
        });

    server.start();
    try {
      int port = server.getAddress().getPort();
      String loginUrl = "http://127.0.0.1:" + port + "/synthetic/login";

      PortalUrlValidator portalUrls = mock(PortalUrlValidator.class);
      when(portalUrls.isAllowedRuntimeUri(any()))
          .thenAnswer(
              invocation -> {
                URI uri = invocation.getArgument(0);
                return uri != null && "127.0.0.1".equals(uri.getHost());
              });

      var authenticator =
          new PlaywrightVulcanBrowserAuthenticator(portalUrls, true, Duration.ofSeconds(15));

      VulcanSessionMaterial material;
      try (var request =
          new VulcanLoginRequest(
              URI.create(loginUrl), "synthetic-login", "synthetic-password".toCharArray())) {
        material = authenticator.authenticate(request);
      }
      long completionMillis = System.currentTimeMillis();

      // 1. Authenticator succeeds and captures session material
      assertThat(material).isNotNull();
      assertThat(material.requestVerificationToken()).isEqualTo("synthetic-token-42");
      assertThat(material.appGuid()).isEqualTo("synthetic-guid-42");
      assertThat(cookiePairs(material)).isEqualTo("SyntheticCookie=synthetic-cookie-value");
      assertThat(material.applicationBaseUri())
          .isEqualTo(URI.create("http://127.0.0.1:" + port + "/synthetic/"));

      // 2. Artificial delay was > 2 seconds (~2.6s)
      assertThat(ARTIFICIAL_DELAY_MS).isGreaterThan(2000);
      assertThat(delayedRequestReceivedAt.get()).isGreaterThan(0L);
      long delayFromSubmission = delayedRequestReceivedAt.get() - submissionReceivedAt.get();
      assertThat(delayFromSubmission).isGreaterThanOrEqualTo(2000);

      // 3. Demonstrates the old fixed 2-second settling delay would have missed this request:
      // At t = 2000ms after submission, delayedRequestReceivedAt was still 0 (no complete request
      // arrived).
      // The old 2-second wait would have evaluated observations at t=2.0s, seen 0 complete
      // requests,
      // and failed closed with PROTOCOL_FAILURE.
      assertThat(delayFromSubmission).isGreaterThan(2000);

      // 4. Real Playwright waitForCondition resolved promptly after delayed request arrival
      // without consuming the full 15-second timeout
      long durationFromSubmission = completionMillis - submissionReceivedAt.get();
      long durationAfterDelayedRequest = completionMillis - delayedRequestReceivedAt.get();
      assertThat(durationFromSubmission).isLessThan(15000);
      assertThat(durationAfterDelayedRequest).isLessThan(10000);
    } finally {
      server.stop(0);
    }
  }
}
