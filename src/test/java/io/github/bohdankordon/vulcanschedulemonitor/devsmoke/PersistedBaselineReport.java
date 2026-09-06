package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import java.util.*;
import tools.jackson.databind.ObjectMapper;

final class PersistedBaselineReport {
  static final Set<String> CATEGORIES =
      Set.of(
          "NOT_AUTHORIZED",
          "INVALID_INPUT",
          "APP_RUNNING",
          "DATABASE_UNAVAILABLE",
          "NO_ACCOUNT",
          "AMBIGUOUS_ACCOUNT",
          "ACCOUNT_NOT_CONNECTED",
          "NO_TARGET",
          "AMBIGUOUS_TARGET",
          "SESSION_UNAVAILABLE",
          "UNSAFE_SESSION",
          "BASELINE_COMPLETED",
          "BUDGET_EXHAUSTED",
          "HARNESS_FAILURE",
          "BUILD_FAILURE",
          "KEY_UNAVAILABLE",
          "CHILD_FAILURE",
          "UNSAFE_OUTPUT_GUARD");
  static final Set<String> SHARED =
      Set.of(
          "result",
          "session.cookieCountBefore",
          "session.cookieCountAfter",
          "session.cookieCountChanged",
          "session.cookieMaterialChanged",
          "javaRequestAttempted",
          "java.statusFamily",
          "java.status429",
          "java.contentFamily",
          "javaOutcome",
          "retryAfterPresent",
          "retryAfterSeconds",
          "javaScheduleRequests",
          "retries");
  final NativeSessionBaselineReport observation = new NativeSessionBaselineReport();
  private final Map<String, Object> own = new LinkedHashMap<>();

  PersistedBaselineReport() {
    own.put("schemaVersion", 1);
    own.put("variant", "PERSISTED_CONNECT_SESSION");
    own.put("category", "NOT_AUTHORIZED");
    for (String key :
        List.of(
            "persistedSessionLoaded",
            "targetResolved",
            "account.connected",
            "account.reconnectRequired",
            "form.weekStartValid",
            "form.weekEndValid")) own.put(key, false);
  }

  void put(String key, Object value) {
    if (!own.containsKey(key)
        || (key.equals("category")
            ? !CATEGORIES.contains(value)
            : !(own.get(key) instanceof Boolean && value instanceof Boolean)))
      throw new IllegalArgumentException("UNSAFE_OUTPUT_GUARD");
    own.put(key, value);
  }

  Map<String, Object> facts() {
    var result = new LinkedHashMap<>(own);
    var safe = observation.facts();
    SHARED.forEach(key -> result.put(key, safe.get(key)));
    return Collections.unmodifiableMap(result);
  }

  String json() {
    return new ObjectMapper().writeValueAsString(facts());
  }
}
