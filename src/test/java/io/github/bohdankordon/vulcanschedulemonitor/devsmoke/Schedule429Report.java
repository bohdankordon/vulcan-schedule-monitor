package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Test-source-only, finite output protocol. Arbitrary strings cannot enter the report. */
public final class Schedule429Report {
  public static final String TITLE = "REAL VULCAN SCHEDULE 429 INVESTIGATION";
  private static final String BOOL = "true|false|UNAVAILABLE";
  private static final String CONTEXT =
      "PLAN_PAGE|HOME_OR_LANDING|JOURNAL_PAGE|OTHER_ALLOWED|UNAVAILABLE";
  public static final Map<String, String> SCHEMA = schema();
  private final Map<String, String> facts = new LinkedHashMap<>();
  private Schedule429Failure.Stage currentStage = Schedule429Failure.Stage.INVESTIGATION_INPUT;
  private Schedule429Failure.Category firstFailure;

  public void stage(Schedule429Failure.Stage stage) {
    currentStage = stage;
  }

  public void fail(Throwable failure) {
    if (firstFailure != null) return;
    firstFailure =
        failure instanceof Schedule429Failure finite
            ? finite.category()
            : failure instanceof com.microsoft.playwright.TimeoutError
                ? (currentStage == Schedule429Failure.Stage.BROWSER_CONTROL_WAIT
                    ? Schedule429Failure.Category.REQUEST_NOT_OBSERVED
                    : Schedule429Failure.Category.NAVIGATION_TIMEOUT)
                : failure instanceof com.microsoft.playwright.PlaywrightException
                    ? Schedule429Failure.Category.PLAYWRIGHT_TRANSIENT
                    : failure
                            instanceof
                            io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection
                                .VulcanAuthenticationException
                        ? Schedule429Failure.Category.UNEXPECTED_PAGE_STATE
                        : Schedule429Failure.Category.INTERNAL_INVARIANT;
    put("stage", currentStage.name());
    put("failureCategory", firstFailure.name());
  }

  public void fail(Schedule429Failure.Stage stage, Throwable failure) {
    if (firstFailure != null) return;
    currentStage = stage;
    fail(failure);
  }

  public void throwIfFailed() {
    if (firstFailure != null) throw new Schedule429Failure(firstFailure);
  }

  public Schedule429Report() {
    put("category", "HARNESS_FAILURE");
    put("result", "FAIL");
    put("browserSource", "NOT_REACHED");
    put("browserScheduleRequests", 0);
    put("javaScheduleRequests", 0);
    put("blockedExtraScheduleRequests", 0);
    put("javaPermitted", false);
    put("decisionCase", "NOT_REACHED");
    put("persistedMonitoringRefererContext", "UNAVAILABLE");
    put("retries", 0);
    put("javaHeaderEvidence", "SYNTHETIC_PRODUCTION_TRANSPORT");
  }

  private static Map<String, String> schema() {
    Map<String, String> s = new LinkedHashMap<>();
    s.put(
        "stage",
        java.util.Arrays.stream(Schedule429Failure.Stage.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining("|")));
    s.put(
        "failureCategory",
        java.util.Arrays.stream(Schedule429Failure.Category.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining("|")));
    s.put(
        "category",
        "COMPARED|BROWSER_RATE_LIMITED|BROWSER_OTHER_FAILURE|INVALID_INPUT|INVALID_CREDENTIALS|MFA_REQUIRED|CAPTCHA_REQUIRED|UNSUPPORTED_AUTH_FLOW|TRANSIENT|PROTOCOL_FAILURE|HARNESS_FAILURE|PLAN_CONTEXT_UNAVAILABLE|NATIVE_TARGET_MISMATCH|BROWSER_REQUEST_TIMEOUT|SCHEDULE_BUDGET_BLOCKED");
    s.put("result", "SUCCESS|FAIL");
    s.put("browserSource", "NATIVE_UI_REQUEST|BROWSER_CONTEXT_FETCH|NOT_REACHED");
    s.put("decisionCase", "1|2|3|4|NOT_REACHED");
    s.put(
        "javaOutcome",
        "SUCCESS|NOT_RUN|AUTHENTICATION_REQUIRED|RATE_LIMITED|SERVER_ERROR|PERMANENT_HTTP|TRANSPORT_ERROR|SESSION_REDIRECT|UNEXPECTED_HTML|PROTOCOL_FAILURE");
    s.put("javaHeaderEvidence", "SYNTHETIC_PRODUCTION_TRANSPORT");
    s.put("javaMaterialContext", "POST_LOGIN_PROJECTION|POST_PLAN_ACTUAL");
    s.put("browserScheduleRequests", "[01]");
    s.put("javaScheduleRequests", "[01]");
    s.put("retries", "0");
    for (String key :
        new String[] {
          "blockedExtraScheduleRequests",
          "classCount",
          "postLoginCookieCount",
          "postPlanCookieCount",
          "browser.cookieCount",
          "java.cookieCount"
        }) s.put(key, "[0-9]{1,6}");
    for (String key :
        new String[] {
          "javaPermitted",
          "cookieSetChanged",
          "cookieNameSetChanged",
          "verificationTokenChanged",
          "appGuidChanged",
          "sameFieldSet",
          "sameTimestampShape",
          "sameWeekBoundarySemantics",
          "sameDataSemanticPosition",
          "sameFormEncoding",
          "planContextConfirmed",
          "browser.jsonParseable",
          "browser.envelopePresent",
          "browser.scheduleArraysPresent"
        }) s.put(key, BOOL);
    for (String key :
        new String[] {
          "persistedMonitoringRefererContext",
          "postLoginRefererContext",
          "postPlanRefererContext",
          "browser.refererContext",
          "java.refererContext"
        }) s.put(key, CONTEXT);
    for (String side : new String[] {"browser", "java"}) {
      s.put(side + ".method", "POST|OTHER|UNAVAILABLE");
      s.put(side + ".statusFamily", "2xx|3xx|4xx|5xx|OTHER|UNAVAILABLE");
      s.put(side + ".status429", BOOL);
      s.put(side + ".contentFamily", "json|html|other|UNAVAILABLE");
      s.put(side + ".formEncoding", "URL_ENCODED|OTHER|UNAVAILABLE");
      for (String date : new String[] {"dataOd", "dataDo", "data"})
        s.put(side + "." + date + "Format", "ISO_T_DATETIME|OTHER|UNAVAILABLE");
      for (String key :
          new String[] {
            "formFieldSetMatchesExpected",
            "weekBoundarySemantics",
            "dataAtWeekStart",
            "xRequestedWithPresent",
            "originPresent",
            "refererPresent",
            "verificationHeaderPresent",
            "appGuidHeaderPresent",
            "contentTypePresent",
            "userAgentPresent",
            "acceptPresent",
            "acceptLanguagePresent",
            "secFetchHeadersPresent",
            "cookieHeaderPresent"
          }) s.put(side + "." + key, BOOL);
    }
    return Map.copyOf(s);
  }

  public void put(String key, Object value) {
    String text = String.valueOf(value);
    String expression = SCHEMA.get(key);
    if (expression == null || !Pattern.matches(expression, text))
      throw new IllegalArgumentException("Invalid diagnostic fact");
    facts.put(key, text);
  }

  public boolean compared() {
    return "COMPARED".equals(facts.get("category"));
  }

  public void print(PrintStream stream) {
    stream.println(TITLE);
    facts.forEach((key, value) -> stream.println(key + "=" + value));
  }

  @Override
  public String toString() {
    return "Schedule429Report[finite facts only]";
  }
}
