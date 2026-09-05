package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import java.util.*;
import java.util.function.Predicate;
import tools.jackson.databind.ObjectMapper;

final class NativeSessionBaselineReport {
  enum Variant {
    UNTOUCHED,
    ACCEPT_STAR_STAR
  }

  private final Map<String, Object> facts = new LinkedHashMap<>();
  private final Variant variant;

  NativeSessionBaselineReport() {
    this(Variant.UNTOUCHED);
  }

  NativeSessionBaselineReport(Variant variant) {
    this.variant = Objects.requireNonNull(variant);
    facts.put("variant", variant.name());
    facts.put("acceptInjected", false);
    facts.put("schemaVersion", 1);
    facts.put("result", "FAIL");
    facts.put("category", "INVALID_INPUT");
    facts.put("nativeEvidenceValidated", false);
    facts.put("session.cookieCount", 0);
    facts.put("session.cookieCountBefore", "UNAVAILABLE");
    facts.put("session.cookieCountAfter", "UNAVAILABLE");
    facts.put("session.cookieCountChanged", "UNAVAILABLE");
    facts.put("session.cookieMaterialChanged", "UNAVAILABLE");
    facts.put("session.refererContext", "UNAVAILABLE");
    facts.put("form.exactFieldSet", false);
    facts.put("form.timestampShapesMatch", false);
    facts.put("form.weekSemanticsMatch", false);
    facts.put("javaRequestAttempted", false);
    facts.put("java.statusFamily", "UNAVAILABLE");
    facts.put("java.status429", "UNAVAILABLE");
    facts.put("java.contentFamily", "UNAVAILABLE");
    facts.put("javaOutcome", "NOT_RUN");
    facts.put("retryAfterPresent", "UNAVAILABLE");
    facts.put("retryAfterSeconds", "UNAVAILABLE");
    facts.put("javaScheduleRequests", 0);
    facts.put("retries", 0);
  }

  Variant variant() {
    return variant;
  }

  void put(String key, Object value) {
    var predicate = schema().get(key);
    if (predicate == null || !predicate.test(value))
      throw new IllegalArgumentException("UNSAFE_OUTPUT_GUARD");
    facts.put(key, value);
  }

  Map<String, Object> facts() {
    validate();
    return Map.copyOf(facts);
  }

  String json() {
    validate();
    return new ObjectMapper().writeValueAsString(facts);
  }

  private void validate() {
    if (!variant.name().equals(facts.get("variant"))
        || (variant == Variant.UNTOUCHED && !Boolean.FALSE.equals(facts.get("acceptInjected")))) {
      throw new IllegalArgumentException("UNSAFE_OUTPUT_GUARD");
    }
    if (!facts.keySet().equals(schema().keySet()))
      throw new IllegalArgumentException("UNSAFE_OUTPUT_GUARD");
    facts.forEach(
        (key, value) -> {
          if (!schema().get(key).test(value))
            throw new IllegalArgumentException("UNSAFE_OUTPUT_GUARD");
        });
  }

  private static Predicate<Object> values(String... options) {
    Set<String> choices = Set.of(options);
    return value -> value instanceof String && choices.contains(value);
  }

  private static Map<String, Predicate<Object>> schema() {
    Map<String, Predicate<Object>> s = new HashMap<>();
    s.put("variant", values("UNTOUCHED", "ACCEPT_STAR_STAR"));
    s.put("acceptInjected", value -> value instanceof Boolean);
    s.put("schemaVersion", value -> Integer.valueOf(1).equals(value));
    s.put("result", values("SUCCESS", "FAIL"));
    s.put(
        "category",
        values(
            "VALIDATION_ONLY",
            "INVALID_INPUT",
            "NOT_AUTHORIZED",
            "INVALID_HAR",
            "INVALID_NATIVE_EVIDENCE",
            "INVALID_SESSION",
            "FORM_MISMATCH",
            "BUDGET_EXHAUSTED",
            "BASELINE_COMPLETED",
            "HARNESS_FAILURE",
            "BUILD_FAILURE",
            "UNSAFE_OUTPUT_GUARD"));
    s.put(
        "session.refererContext",
        values("PLAN_PAGE", "JOURNAL_PAGE", "HOME_OR_LANDING", "OTHER_ALLOWED", "UNAVAILABLE"));
    s.put(
        "session.cookieCount",
        value -> value instanceof Integer count && count >= 0 && count <= 1000);
    for (String key : List.of("session.cookieCountBefore", "session.cookieCountAfter"))
      s.put(
          key,
          value ->
              "UNAVAILABLE".equals(value)
                  || value instanceof Integer count && count >= 0 && count <= 1000);
    for (String key : List.of("session.cookieCountChanged", "session.cookieMaterialChanged"))
      s.put(key, value -> value instanceof Boolean || "UNAVAILABLE".equals(value));
    for (String key :
        List.of(
            "nativeEvidenceValidated",
            "form.exactFieldSet",
            "form.timestampShapesMatch",
            "form.weekSemanticsMatch",
            "javaRequestAttempted")) s.put(key, value -> value instanceof Boolean);
    s.put("java.statusFamily", values("2xx", "3xx", "4xx", "5xx", "UNAVAILABLE"));
    s.put("java.status429", value -> value instanceof Boolean || "UNAVAILABLE".equals(value));
    s.put("java.contentFamily", values("json", "html", "other", "UNAVAILABLE"));
    s.put(
        "javaOutcome",
        values(
            "SUCCESS",
            "RATE_LIMITED",
            "AUTHENTICATION_REQUIRED",
            "SESSION_REDIRECT",
            "UNEXPECTED_HTML",
            "SERVER_ERROR",
            "PERMANENT_HTTP",
            "TRANSPORT_ERROR",
            "PROTOCOL_FAILURE",
            "NOT_RUN"));
    s.put("retryAfterPresent", value -> value instanceof Boolean || "UNAVAILABLE".equals(value));
    s.put(
        "retryAfterSeconds",
        value -> "UNAVAILABLE".equals(value) || value instanceof Long n && n >= 0 && n <= 31536000);
    s.put(
        "javaScheduleRequests",
        value -> Integer.valueOf(0).equals(value) || Integer.valueOf(1).equals(value));
    s.put("retries", value -> Integer.valueOf(0).equals(value));
    return s;
  }
}
