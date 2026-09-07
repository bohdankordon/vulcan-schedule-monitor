package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import java.util.*;
import tools.jackson.databind.ObjectMapper;

final class MonitoringSequenceReport {
  static final Set<String> CATEGORIES;
  static final Set<String> FIDELITY_BOOLEANS =
      Set.of(
          "current.persistence.applicationBaseSame",
          "current.persistence.refererSame",
          "current.persistence.verificationTokenSame",
          "current.persistence.appGuidSame",
          "current.persistence.cookieMaterialSame",
          "current.persistence.cookieCountChanged",
          "current.liveCookieTopology.duplicateNamePresent",
          "current.liveCookieTopology.duplicateNameDifferentPathPresent",
          "current.liveCookieTopology.duplicateNameDifferentDomainPresent",
          "current.materialRoundTrip.cookieMaterialSame");
  static final Set<String> FIDELITY_COUNTS =
      Set.of(
          "current.persistence.expectedCookieCount",
          "current.persistence.actualCookieCount",
          "current.liveCookieTopology.totalCookieCount",
          "current.materialRoundTrip.cookieCountBefore",
          "current.materialRoundTrip.cookieCountAfter");

  static {
    var categories = new HashSet<>(PersistedBaselineReport.CATEGORIES);
    categories.addAll(
        Set.of(
            "SEQUENCE_COMPLETED",
            "ROLLBACK_VERIFICATION_FAILED",
            "TRANSACTION_REQUIRED",
            "INVALID_PLAN"));
    CATEGORIES = Set.copyOf(categories);
  }

  private final Map<String, Object> facts = new LinkedHashMap<>();
  private final List<Map<String, Object>> requests = new ArrayList<>();

  MonitoringSequenceReport() {
    facts.put("schemaVersion", 1);
    facts.put("variant", "PERSISTED_MONITORING_SEQUENCE");
    facts.put("result", "FAIL");
    facts.put("category", "NOT_AUTHORIZED");
    for (String key :
        List.of(
            "persistedSessionLoaded",
            "targetResolved",
            "account.connected",
            "account.reconnectRequired",
            "gateInitiallyClear",
            "accountBlockedAfterCurrent",
            "spacingAppliedBeforeNext")) facts.put(key, false);
    for (String key :
        List.of(
            "current.sessionPersistedAfterSuccess",
            "next.loadedPostCurrentSession",
            "databaseSessionRestoredAfterRollback")) facts.put(key, "UNAVAILABLE");
    facts.put("scopeCount", 0);
    facts.put("spacingConfiguredMillis", 500);
    facts.put("totalScheduleRequests", 0);
    facts.put("retries", 0);
    facts.put("current.outcome", "NOT_REACHED");
    facts.put("next.outcome", "NOT_REACHED");
    facts.put("next.disposition", "NOT_REACHED");
    FIDELITY_BOOLEANS.stream().sorted().forEach(key -> facts.put(key, "UNAVAILABLE"));
    FIDELITY_COUNTS.stream().sorted().forEach(key -> facts.put(key, "UNAVAILABLE"));
  }

  void put(String key, Object value) {
    if (!facts.containsKey(key)) throw unsafe();
    boolean valid;
    if (FIDELITY_BOOLEANS.contains(key))
      valid = value instanceof Boolean || "UNAVAILABLE".equals(value);
    else if (FIDELITY_COUNTS.contains(key))
      valid = "UNAVAILABLE".equals(value) || value instanceof Integer n && n >= 0 && n <= 1000;
    else if (key.equals("category")) valid = CATEGORIES.contains(value);
    else if (key.equals("result")) valid = Set.of("SUCCESS", "FAIL").contains(value);
    else if (key.endsWith(".outcome"))
      valid =
          Set.of(
                  "NOT_REACHED",
                  "SUCCESS",
                  "BASELINE_ESTABLISHED",
                  "TRANSITIONS",
                  "AUTHENTICATION_REQUIRED",
                  "TRANSIENT_RECOVERY_FAILURE",
                  "DEFERRED_RATE_LIMIT",
                  "TRANSIENT_FAILURE_EXHAUSTED",
                  "INTERRUPTED",
                  "PERMANENT_FAILURE",
                  "PROTOCOL_FAILURE",
                  "BUDGET_EXHAUSTED")
              .contains(value);
    else if (key.equals("next.disposition"))
      valid =
          Set.of(
                  "DISPATCHED",
                  "SKIPPED_ACCOUNT_BLOCKED",
                  "SKIPPED_INTERRUPTED",
                  "SKIPPED_BUDGET",
                  "NOT_REACHED")
              .contains(value);
    else if (Set.of(
            "current.sessionPersistedAfterSuccess",
            "next.loadedPostCurrentSession",
            "databaseSessionRestoredAfterRollback")
        .contains(key)) valid = value instanceof Boolean || "UNAVAILABLE".equals(value);
    else if (facts.get(key) instanceof Boolean) valid = value instanceof Boolean;
    else if (key.equals("scopeCount"))
      valid = Integer.valueOf(0).equals(value) || Integer.valueOf(2).equals(value);
    else if (Set.of("totalScheduleRequests", "retries").contains(key))
      valid = value instanceof Integer n && n >= 0 && n <= 2;
    else valid = false;
    if (!valid) throw unsafe();
    facts.put(key, value);
  }

  void request(String scope, int attempt, NativeSessionBaselineReport report) {
    if (!Set.of("CURRENT", "NEXT").contains(scope)
        || attempt < 1
        || attempt > 2
        || requests.size() == 2) throw unsafe();
    var safe = report.facts();
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("scope", scope);
    entry.put("attemptNumber", attempt);
    for (String key :
        List.of(
            "java.statusFamily",
            "java.status429",
            "java.contentFamily",
            "javaOutcome",
            "retryAfterPresent",
            "retryAfterSeconds",
            "session.cookieCountBefore",
            "session.cookieCountAfter",
            "session.cookieCountChanged",
            "session.cookieMaterialChanged")) {
      String target =
          key.equals("javaOutcome") ? "outcome" : key.replace("java.", "").replace("session.", "");
      entry.put(target, safe.get(key));
    }
    requests.add(Collections.unmodifiableMap(entry));
  }

  Map<String, Object> facts() {
    var safe = new LinkedHashMap<>(facts);
    safe.put("requests", List.copyOf(requests));
    return Collections.unmodifiableMap(safe);
  }

  String json() {
    return new ObjectMapper().writeValueAsString(facts());
  }

  private static IllegalArgumentException unsafe() {
    return new IllegalArgumentException("UNSAFE_OUTPUT_GUARD");
  }
}
