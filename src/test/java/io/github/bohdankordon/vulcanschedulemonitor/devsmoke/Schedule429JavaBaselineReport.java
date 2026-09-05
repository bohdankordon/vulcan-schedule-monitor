package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Separate finite protocol; the previous two-transport protocol is unchanged. */
public final class Schedule429JavaBaselineReport {
  public static final String TITLE = "REAL VULCAN SCHEDULE 429 JAVA BASELINE";

  public enum Stage {
    INPUT,
    AUTHENTICATION,
    CATALOG_DISCOVERY,
    TARGET_SELECTION,
    JAVA_BASELINE_SETUP,
    JAVA_BASELINE
  }

  public static final Map<String, String> SCHEMA = schema();
  private final Map<String, String> facts = new LinkedHashMap<>();
  private Stage stage = Stage.INPUT;
  private boolean failed;

  public Schedule429JavaBaselineReport() {
    put("category", "HARNESS_FAILURE");
    put("result", "FAIL");
    put("decisionCase", "NOT_REACHED");
    put("browserScheduleRequests", 0);
    put("javaScheduleRequests", 0);
    put("retries", 0);
    put("unexpectedBrowserScheduleTraffic", false);
    put("browserClosedBeforeVerification", false);
    put("javaOutcome", "NOT_RUN");
    put("java.statusFamily", "UNAVAILABLE");
    put("java.status429", "UNAVAILABLE");
    put("retryAfterPresent", "UNAVAILABLE");
    put("javaMaterialContext", "NOT_USED");
  }

  private static Map<String, String> schema() {
    var s = new LinkedHashMap<String, String>();
    s.put(
        "category",
        "BASELINE_COMPLETED|HARNESS_FAILURE|INVALID_INPUT|INVALID_CREDENTIALS|MFA_REQUIRED|CAPTCHA_REQUIRED|UNSUPPORTED_AUTH_FLOW|TRANSIENT|PROTOCOL_FAILURE|UNEXPECTED_BROWSER_SCHEDULE_TRAFFIC");
    s.put("result", "SUCCESS|FAIL");
    s.put("decisionCase", "J1|J2|J3|J4|NOT_REACHED");
    s.put(
        "javaOutcome",
        "SUCCESS|NOT_RUN|RATE_LIMITED|AUTHENTICATION_REQUIRED|SESSION_REDIRECT|UNEXPECTED_HTML|SERVER_ERROR|PERMANENT_HTTP|TRANSPORT_ERROR|PROTOCOL_FAILURE");
    s.put("java.statusFamily", "2xx|3xx|4xx|5xx|OTHER|UNAVAILABLE");
    s.put("java.status429", "true|false|UNAVAILABLE");
    s.put("retryAfterPresent", "true|false|UNAVAILABLE");
    s.put("retryAfterSeconds", "[0-9]{1,19}");
    s.put("javaMaterialContext", "NOT_USED|PRE_VERIFICATION_POST_LOGIN");
    s.put("browserScheduleRequests", "0");
    s.put("javaScheduleRequests", "[01]");
    s.put("retries", "0");
    s.put(
        "stage",
        java.util.Arrays.stream(Stage.values())
            .map(Enum::name)
            .collect(java.util.stream.Collectors.joining("|")));
    s.put(
        "failureCategory",
        "SECURITY_INVARIANT|INTERNAL_INVARIANT|PLAYWRIGHT_TRANSIENT|AUTHENTICATION_FAILURE");
    for (String key : new String[] {"postLoginCookieCount", "verifiedCookieCount", "classCount"})
      s.put(key, "[0-9]{1,6}");
    for (String key :
        new String[] {
          "applicationBaseChanged",
          "refererChangedExact",
          "verificationTokenChanged",
          "appGuidChanged",
          "cookieMaterialChanged",
          "cookieCountChanged",
          "unexpectedBrowserScheduleTraffic",
          "browserClosedBeforeVerification"
        }) s.put(key, "true|false");
    for (String key : new String[] {"postLoginRefererContext", "verifiedRefererContext"})
      s.put(key, "HOME_OR_LANDING|JOURNAL_PAGE|PLAN_PAGE|OTHER_ALLOWED|UNAVAILABLE");
    return Map.copyOf(s);
  }

  public void put(String key, Object value) {
    String text = String.valueOf(value);
    String pattern = SCHEMA.get(key);
    if (pattern == null || !Pattern.matches(pattern, text))
      throw new IllegalArgumentException("Invalid baseline fact");
    facts.put(key, text);
  }

  public void stage(Stage next) {
    stage = next;
  }

  public void failure(Throwable failure) {
    if (failed) return;
    failed = true;
    put("stage", stage.name());
    put(
        "failureCategory",
        failure instanceof Schedule429Failure finite
                && finite.category() == Schedule429Failure.Category.SECURITY_INVARIANT
            ? "SECURITY_INVARIANT"
            : failure instanceof com.microsoft.playwright.PlaywrightException
                ? "PLAYWRIGHT_TRANSIENT"
                : failure
                        instanceof
                        io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection
                            .VulcanAuthenticationException
                    ? "AUTHENTICATION_FAILURE"
                    : "INTERNAL_INVARIANT");
  }

  public boolean succeeded() {
    return "SUCCESS".equals(facts.get("result"));
  }

  public void print(PrintStream out) {
    out.println(TITLE);
    facts.forEach((key, value) -> out.println(key + "=" + value));
  }

  @Override
  public String toString() {
    return "Schedule429JavaBaselineReport[finite facts only]";
  }
}
