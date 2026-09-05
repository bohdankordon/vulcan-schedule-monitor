package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import com.sun.net.httpserver.HttpServer;
import io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Budget;
import io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Report;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Opt-in Chromium check, bound exclusively to loopback; never reads input or authenticates. */
@EnabledIfSystemProperty(named = "schedule429.localBrowserTests", matches = "true")
class Schedule429LocalBrowserTest {
  @org.junit.jupiter.api.Test
  void originalUnicodeSelectorIsRejectedBeforeBrowserTraffic() {
    try (var playwright = Playwright.create();
        var chromium = playwright.chromium().launch();
        var context = chromium.newContext();
        var page = context.newPage()) {
      var network = new AtomicInteger();
      context.route(
          "**/*",
          route -> {
            network.incrementAndGet();
            route.abort();
          });
      // This is the exact original failing operation, isolated from all provider/UI assumptions.
      assertThatThrownBy(
              () -> page.getByText(java.util.regex.Pattern.compile("(?iu)^plan lekcji$")))
          .isInstanceOf(PlaywrightException.class)
          .hasMessageContaining("Unexpected RegEx flag");
      assertThat(network).hasValue(0);
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "200,json",
    "429,json",
    "403,json",
    "500,json",
    "200,html",
    "200,malformed",
    "200,envelope"
  })
  void existingPageFetchUsesPostLoginAjaxHeadersAndNaturalCookies(int status, String content)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/synthetic/");
    URI current = base.resolve("Home.mvc/Index");
    var scheduleRequests = new AtomicInteger();
    var documentRequests = new AtomicInteger();
    var formMatches = new java.util.concurrent.atomic.AtomicBoolean();
    var headersMatch = new java.util.concurrent.atomic.AtomicBoolean();
    var cookieNatural = new java.util.concurrent.atomic.AtomicBoolean();
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            boolean schedule = exchange.getRequestURI().getPath().endsWith("/GetPlanLekcjiContext");
            byte[] body;
            int result;
            if (schedule) {
              scheduleRequests.incrementAndGet();
              String form =
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
              formMatches.set(
                  io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Structure.form(
                          "POST",
                          form,
                          exchange.getRequestHeaders().getFirst("Content-Type"),
                          LocalDate.of(2026, 8, 31))
                      .equals(
                          new io.github.bohdankordon.vulcanschedulemonitor.devsmoke
                              .Schedule429Structure.FormFacts(
                              true, true, true, true, true, true, true)));
              var headers = exchange.getRequestHeaders();
              headersMatch.set(
                  "synthetic-token".equals(headers.getFirst("X-V-RequestVerificationToken"))
                      && "synthetic-guid".equals(headers.getFirst("X-V-AppGuid"))
                      && "XMLHttpRequest".equals(headers.getFirst("X-Requested-With")));
              cookieNatural.set("Synthetic=value".equals(headers.getFirst("Cookie")));
              String response =
                  switch (content) {
                    case "html" -> "<html>Local authentication response</html>";
                    case "malformed" -> "invalid-json";
                    case "envelope" -> "{\"success\":false}";
                    default ->
                        "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}";
                  };
              body = response.getBytes(StandardCharsets.UTF_8);
              exchange
                  .getResponseHeaders()
                  .set("Content-Type", content.equals("html") ? "text/html" : "application/json");
              result = status;
            } else {
              documentRequests.incrementAndGet();
              body = "<html><body>Local fixture</body></html>".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "text/html");
              exchange.getResponseHeaders().set("Set-Cookie", "Synthetic=value; Path=/");
              result = 200;
            }
            exchange.sendResponseHeaders(result, body.length);
            exchange.getResponseBody().write(body);
          }
        });
    server.start();
    try (var playwright = Playwright.create();
        var chromium = playwright.chromium().launch();
        var context = chromium.newContext();
        var page = context.newPage()) {
      var budget = new Schedule429Budget();
      var report = new Schedule429Report();
      var driver = new Schedule429Browser(VulcanDiagnostics.NONE, report, budget);
      var urls = mock(PortalUrlValidator.class);
      when(urls.isAllowedRuntimeUri(any()))
          .thenAnswer(
              call -> {
                URI uri = call.getArgument(0);
                return uri.getScheme().equals("http")
                    && uri.getRawAuthority().equals(base.getRawAuthority());
              });
      set(driver, "portalUrls", urls);
      set(driver, "context", context);
      set(driver, "page", page);
      context.route("**/*", route -> invoke(driver, "guardCredentialRequest", Route.class, route));
      context.onResponse(response -> invoke(driver, "observeResponse", Response.class, response));
      var install = Schedule429Browser.class.getDeclaredMethod("installRedirectGuard");
      install.setAccessible(true);
      install.invoke(driver);
      page.navigate(current.toString());
      // Deliberately different cookie material proves the browser uses its own HTTP cookie jar.
      var material =
          new io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial(
              base,
              status == 429 ? base : current,
              "synthetic-token",
              "synthetic-guid",
              "CapturedOnly=not-in-browser");
      var selected =
          new io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass(
              1,
              1,
              "Synthetic class",
              "Synthetic unit",
              1,
              2026,
              LocalDate.of(2026, 9, 1),
              LocalDate.of(2027, 8, 31));
      driver.control(material, java.util.List.of(selected), LocalDate.of(2026, 8, 31));
      assertThat(page.url()).isEqualTo(current.toString());
      assertThat(documentRequests).hasValue(1);
      assertThat(driver.scheduleStatus()).isEqualTo(status);
      assertThat(budget.browserRequests()).isEqualTo(1);
      assertThat(budget.javaPermitted()).isEqualTo(status == 200 && content.equals("json"));
      assertThat(scheduleRequests).hasValue(1);
      assertThat(formMatches).isTrue();
      assertThat(headersMatch).isTrue();
      assertThat(cookieNatural).isTrue();
      var output = new java.io.ByteArrayOutputStream();
      report.print(new java.io.PrintStream(output));
      assertThat(output.toString(StandardCharsets.UTF_8))
          .contains(
              "browserSource=BROWSER_CONTEXT_FETCH",
              "browser.verificationHeaderPresent=true",
              "browser.appGuidHeaderPresent=true",
              "browser.xRequestedWithPresent=true",
              "browser.cookieCount=1",
              "browserRefererMatchesCapturedReferer=" + (status != 429))
          .doesNotContain(
              "synthetic-token",
              "synthetic-guid",
              "CapturedOnly",
              "Synthetic=",
              current.toString());
    } finally {
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 429, 307})
  void chromiumCannotSendSecondRequestOrFollowScheduleRedirect(int status) throws Exception {
    var count = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/synthetic/");
    server.createContext(
        "/synthetic/index",
        exchange -> {
          try (exchange) {
            byte[] page =
                "<html><button onclick=\"for(let i=0;i&lt;2;i++)fetch('PlanLekcji.mvc/GetPlanLekcjiContext',{method:'POST',body:new URLSearchParams({dataOd:'2026-08-31T00:00:00',dataDo:'2026-09-06T00:00:00',data:'2026-09-05T00:00:00',idDziennik:'1'})}).catch(()=>{})\">Schedule</button></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
          }
        });
    server.createContext(
        "/synthetic/PlanLekcji.mvc/GetPlanLekcjiContext",
        exchange -> {
          try (exchange) {
            count.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] json =
                "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status == 307)
              exchange
                  .getResponseHeaders()
                  .set("Location", base.resolve("PlanLekcji.mvc/GetPlanLekcjiContext").toString());
            exchange.sendResponseHeaders(status, json.length);
            exchange.getResponseBody().write(json);
          }
        });
    server.start();
    try (var playwright = Playwright.create();
        var chromium = playwright.chromium().launch();
        var context =
            chromium.newContext(
                new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK));
        var page = context.newPage()) {
      var budget = new Schedule429Budget();
      var report = new Schedule429Report();
      var driver = new Schedule429Browser(VulcanDiagnostics.NONE, report, budget);
      // Test-only allowlist: only this exact loopback origin. The real constructor is unchanged.
      var urls = mock(PortalUrlValidator.class);
      when(urls.isAllowedRuntimeUri(any()))
          .thenAnswer(
              call -> {
                URI uri = call.getArgument(0);
                return uri.getScheme().equals("http")
                    && uri.getHost().equals("127.0.0.1")
                    && uri.getPort() == base.getPort();
              });
      set(driver, "portalUrls", urls);
      set(driver, "context", context);
      set(driver, "page", page);
      set(driver, "application", base);
      set(driver, "week", LocalDate.of(2026, 8, 31));
      var journals = Schedule429Browser.class.getDeclaredField("allowedJournals");
      journals.setAccessible(true);
      ((Set<Long>) journals.get(driver)).add(1L);
      budget.arm();
      context.route("**/*", route -> invoke(driver, "guardCredentialRequest", Route.class, route));
      context.onResponse(response -> invoke(driver, "observeResponse", Response.class, response));
      var install = Schedule429Browser.class.getDeclaredMethod("installRedirectGuard");
      install.setAccessible(true);
      install.invoke(driver);
      page.navigate(base.resolve("index").toString());
      page.getByText("Schedule").click();
      page.waitForCondition(
          () -> driver.scheduleStatus() != 0, new Page.WaitForConditionOptions().setTimeout(10000));
      page.waitForTimeout(300);
      assertThat(driver.scheduleStatus()).isEqualTo(status);
      assertThat(count).hasValue(1);
      assertThat(budget.browserRequests()).isEqualTo(1);
      assertThat(budget.javaPermitted()).isEqualTo(status == 200);
    } finally {
      server.stop(0);
    }
  }

  private static void set(Object target, String name, Object value) throws Exception {
    var field = Schedule429Browser.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static void invoke(Object target, String name, Class<?> type, Object arg) {
    try {
      var method = Schedule429Browser.class.getDeclaredMethod(name, type);
      method.setAccessible(true);
      method.invoke(target, arg);
    } catch (Exception failure) {
      throw new IllegalStateException("Synthetic callback failure", failure);
    }
  }
}
