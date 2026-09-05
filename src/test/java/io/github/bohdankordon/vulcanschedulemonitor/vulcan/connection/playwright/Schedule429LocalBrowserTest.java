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
  @ValueSource(ints = {200, 429, 404})
  void catalogToControlUsesOneNativeFetchWithoutUiSelectorsOrJquery(int status) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/synthetic/");
    var scheduleRequests = new AtomicInteger();
    var formMatches = new java.util.concurrent.atomic.AtomicBoolean();
    var cookiePresent = new java.util.concurrent.atomic.AtomicBoolean();
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
                  io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Structure
                      .formValues(form)
                      .keySet()
                      .equals(Set.of("dataOd", "dataDo", "data", "idDziennik")));
              cookiePresent.set(exchange.getRequestHeaders().getFirst("Cookie") != null);
              body =
                  "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}"
                      .getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              result = status;
            } else {
              // No matching class/menu text, UI handler, or jQuery. Cookie delivered by normal
              // HTTP.
              body = "<html><body>Local fixture</body></html>".getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "text/html");
              exchange.getResponseHeaders().set("Set-Cookie", "Synthetic=value; Path=/");
              result =
                  status == 404 && exchange.getRequestURI().getPath().endsWith("PlanLekcji.mvc")
                      ? 404
                      : 200;
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
      page.navigate(base.resolve("index").toString());
      var material =
          new io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial(
              base, base, "synthetic-token", "synthetic-guid", "Synthetic=value");
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
      var verified =
          new io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VerifiedVulcanSession(
              material, java.util.List.of(selected));
      if (status == 404) {
        assertThatThrownBy(() -> driver.control(verified, LocalDate.of(2026, 8, 31)))
            .isInstanceOf(
                io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Failure.class);
        var output = new java.io.ByteArrayOutputStream();
        report.print(new java.io.PrintStream(output));
        assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("stage=PLAN_CONTEXT_NAVIGATION", "failureCategory=NOT_FOUND");
        assertThat(scheduleRequests).hasValue(0);
      } else {
        driver.control(verified, LocalDate.of(2026, 8, 31));
        assertThat(driver.scheduleStatus()).isEqualTo(status);
        assertThat(budget.browserRequests()).isEqualTo(1);
        assertThat(budget.javaPermitted()).isEqualTo(status == 200);
        assertThat(scheduleRequests).hasValue(1);
        assertThat(formMatches).isTrue();
        assertThat(cookiePresent).isTrue();
        var output = new java.io.ByteArrayOutputStream();
        report.print(new java.io.PrintStream(output));
        assertThat(output.toString(StandardCharsets.UTF_8))
            .contains("browserSource=BROWSER_CONTEXT_FETCH");
        // Production capture keeps the last complete authenticated observation, not an invented
        // Referer.
        var field = Schedule429Browser.class.getDeclaredField("observations");
        field.setAccessible(true);
        ((java.util.List<BrowserRequestObservation>) field.get(driver))
            .add(
                new BrowserRequestObservation(
                    base.resolve("Home.mvc/GetCache"),
                    base.toString(),
                    "synthetic-token",
                    "synthetic-guid"));
        if (status == 200) assertThat(driver.postPlanMaterial().refererUri()).isEqualTo(base);
      }
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
