package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.Route;
import io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Budget;
import io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Report;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Schedule429BrowserTest {
  private final Schedule429Budget budget = new Schedule429Budget();
  private final Schedule429Report report = new Schedule429Report();
  private final Schedule429Browser browser =
      new Schedule429Browser(VulcanDiagnostics.NONE, report, budget);
  private static final String BASE = "https://school.vulcan.net.pl/synthetic/";

  @Test
  void realRouteAllowsOneEligibleRequestAndAbortsAllAdditionalRequests() throws Exception {
    arm();
    var first = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    guard(first);
    verify(first).resume();
    verify(first, never()).abort();
    for (int i = 0; i < 4; i++) {
      var extra = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
      guard(extra);
      verify(extra).abort();
      verify(extra, never()).resume();
    }
    assertThat(budget.browserRequests()).isEqualTo(1);
  }

  @Test
  void routeBlocksUnarmedAndForeignTargetsBeforeAnyScheduleNetwork() throws Exception {
    var unarmed = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    guard(unarmed);
    verify(unarmed).abort();
    arm();
    for (String url :
        new String[] {
          "https://external.example/synthetic/PlanLekcji.mvc/GetPlanLekcjiContext",
          BASE + "other/PlanLekcji.mvc/GetPlanLekcjiContext"
        }) {
      var request = route(url, 1);
      guard(request);
      verify(request).abort();
    }
    var foreign = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 2);
    guard(foreign);
    verify(foreign).abort();
    var nextWeek = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    when(nextWeek.request().postData())
        .thenReturn(
            "dataOd=2026-09-07T00:00:00&dataDo=2026-09-13T00:00:00&data=2026-09-07T00:00:00&idDziennik=1");
    guard(nextWeek);
    verify(nextWeek).abort();
    assertThat(budget.browserRequests()).isZero();
  }

  @Test
  void browser429StopsTrafficAndDoesNotReadBodyOrPermitJava() throws Exception {
    arm();
    var first = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    guard(first);
    Response response = mock(Response.class);
    Request sent = first.request();
    String sentUrl = sent.url();
    when(response.url()).thenReturn(sentUrl);
    when(response.request()).thenReturn(sent);
    when(response.status()).thenReturn(429);
    when(response.headerValue("content-type")).thenReturn("text/html");
    invoke("observeResponse", Response.class, response);
    assertThat(browser.scheduleStatus()).isEqualTo(429);
    assertThat(budget.javaPermitted()).isFalse();
    verify(response, never()).body();
    var other = route(BASE + "Home.mvc/RefreshSession", 1);
    guard(other);
    verify(other).abort();
  }

  @Test
  void browser2xxRequiresExpectedJsonBeforeJavaCanRun() throws Exception {
    arm();
    var first = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    guard(first);
    Response response = mock(Response.class);
    Request sent = first.request();
    String sentUrl = sent.url();
    when(response.url()).thenReturn(sentUrl);
    when(response.request()).thenReturn(sent);
    when(response.status()).thenReturn(200);
    when(response.headerValue("content-type")).thenReturn("application/json");
    when(response.body())
        .thenReturn(
            "{\"success\":true,\"data\":{\"planLekcji\":[],\"planLekcjiZeZmianami\":[]}}"
                .getBytes());
    invoke("observeResponse", Response.class, response);
    assertThat(budget.javaPermitted()).isTrue();
  }

  @Test
  void diagnosticCredentialPoliciesStayIdenticalToReviewedProductionHelpers() throws Exception {
    String production =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/main/java/io/github/bohdankordon/vulcanschedulemonitor/vulcan/connection/playwright/PlaywrightVulcanBrowserAuthenticator.java"));
    String diagnostic =
        java.nio.file.Files.readString(
            java.nio.file.Path.of(
                "src/test/java/io/github/bohdankordon/vulcanschedulemonitor/vulcan/connection/playwright/Schedule429Browser.java"));
    for (String signature :
        new String[] {
          "private VerifiedLoginForm requireSafeLoginForm",
          "private void requireSafeSubmission",
          "private Locator locateDirectLogin",
          "private void requireAllowedPage",
          "static void rejectInteractiveSecurity"
        }) {
      assertThat(methodText(diagnostic, signature)).isEqualTo(methodText(production, signature));
    }
  }

  @Test
  void endpointIsSameOriginAndIndependentOfCurrentPageRoute() {
    URI base = URI.create(BASE);
    assertThat(Schedule429Browser.scheduleEndpoint(base, base.resolve("Home.mvc/Index")))
        .isEqualTo(base.resolve("PlanLekcji.mvc/GetPlanLekcjiContext"));
    assertThatThrownBy(
            () ->
                Schedule429Browser.scheduleEndpoint(base, URI.create("https://external.example/")))
        .isInstanceOf(
            io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429Failure.class);
  }

  @Test
  void extraFormFieldsCannotConsumeTheBrowserPermit() throws Exception {
    arm();
    var request = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    String body = request.request().postData();
    when(request.request().postData()).thenReturn(body + "&extra=value");
    guard(request);
    verify(request).abort();
    assertThat(budget.browserRequests()).isZero();
  }

  @Test
  void responseObservationFailureIsFiniteAndCannotPermitJava() throws Exception {
    arm();
    var route = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    guard(route);
    var response = mock(Response.class);
    var sent = route.request();
    String url = sent.url();
    when(response.url()).thenReturn(url);
    when(response.request()).thenReturn(sent);
    when(response.status()).thenReturn(200);
    when(response.headerValue("content-type"))
        .thenThrow(new com.microsoft.playwright.PlaywrightException("private value"));
    invoke("observeResponse", Response.class, response);
    assertThat(budget.javaPermitted()).isFalse();
    var output = new java.io.ByteArrayOutputStream();
    report.print(new java.io.PrintStream(output));
    assertThat(output.toString())
        .contains("stage=BROWSER_CONTROL_WAIT", "failureCategory=PLAYWRIGHT_TRANSIENT")
        .doesNotContain("private value");
  }

  @Test
  void unsafeControlRequestFailsClosedBeforeAnyScheduleRequest() throws Exception {
    arm();
    var unsafe = route("https://external.example/synthetic/PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    guard(unsafe);
    verify(unsafe).abort();
    var next = route(BASE + "PlanLekcji.mvc/GetPlanLekcjiContext", 1);
    guard(next);
    verify(next).abort();
    assertThat(budget.browserRequests()).isZero();
    var output = new java.io.ByteArrayOutputStream();
    report.print(new java.io.PrintStream(output));
    assertThat(output.toString())
        .contains("stage=BROWSER_CONTROL_TRIGGER", "failureCategory=SECURITY_INVARIANT")
        .doesNotContain("external.example");
  }

  private static String methodText(String source, String signature) {
    int start = source.indexOf(signature),
        body = source.indexOf('{', start),
        depth = 1,
        end = body + 1;
    while (depth > 0) {
      char c = source.charAt(end++);
      if (c == '{') depth++;
      if (c == '}') depth--;
    }
    return source.substring(start, end).replaceAll("\\s+", " ");
  }

  private void arm() throws Exception {
    set("application", URI.create(BASE));
    set("week", LocalDate.of(2026, 8, 31));
    var field = Schedule429Browser.class.getDeclaredField("allowedJournals");
    field.setAccessible(true);
    ((Set<Long>) field.get(browser)).add(1L);
    budget.arm();
  }

  private Route route(String uri, long journal) {
    Request request = mock(Request.class);
    when(request.url()).thenReturn(uri);
    when(request.method()).thenReturn("POST");
    when(request.postData())
        .thenReturn(
            "dataOd=2026-08-31T00:00:00&dataDo=2026-09-06T00:00:00&data=2026-09-05T00:00:00&idDziennik="
                + journal);
    when(request.headerValue("content-type")).thenReturn("application/x-www-form-urlencoded");
    when(request.allHeaders())
        .thenReturn(Map.of("cookie", "Synthetic=value", "referer", BASE + "PlanLekcji.mvc"));
    Route route = mock(Route.class);
    when(route.request()).thenReturn(request);
    return route;
  }

  private void guard(Route route) throws Exception {
    invoke("guardCredentialRequest", Route.class, route);
  }

  private void invoke(String name, Class<?> parameter, Object value) throws Exception {
    var method = Schedule429Browser.class.getDeclaredMethod(name, parameter);
    method.setAccessible(true);
    method.invoke(browser, value);
  }

  private void set(String name, Object value) throws Exception {
    var field = Schedule429Browser.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(browser, value);
  }
}
