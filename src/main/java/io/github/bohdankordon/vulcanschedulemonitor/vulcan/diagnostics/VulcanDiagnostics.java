package io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import java.util.function.Supplier;

/** Internal opt-in observations. No method accepts provider text, secrets, URIs, or exceptions. */
public interface VulcanDiagnostics {
  VulcanDiagnostics NONE = new VulcanDiagnostics() {};

  enum Stage {
    PORTAL_VALIDATION,
    BROWSER_AUTH,
    SESSION_CAPTURE,
    SESSION_MATERIAL_RECONSTRUCTION,
    VERIFY_CACHE_REQUEST,
    VERIFY_CACHE_PARSE,
    VERIFY_SCHOOL_YEAR,
    VERIFY_TREE_REQUEST,
    VERIFY_TREE_PARSE,
    SESSION_SNAPSHOT,
    VERIFIED
  }

  enum StatusFamily {
    INFORMATIONAL,
    SUCCESS,
    REDIRECT,
    CLIENT_ERROR,
    SERVER_ERROR,
    OTHER
  }

  enum ContentFamily {
    JSON,
    HTML,
    OTHER
  }

  enum CacheFailure {
    PERIODS_SCHEMA,
    PERIOD_ID_SCHEMA,
    PERIOD_NUMBER_SCHEMA,
    PERIOD_START_SCHEMA,
    PERIOD_END_SCHEMA,
    PERIOD_START_TIME_FORMAT,
    PERIOD_END_TIME_FORMAT,
    PERIOD_START_TIME_ONLY,
    PERIOD_END_TIME_ONLY,
    PERIOD_NUMBER_RANGE,
    DUPLICATE_PERIOD_ID
  }

  default void begin(Stage stage) {}

  default void pass(Stage stage) {}

  default void response(Stage request, StatusFamily status, ContentFamily content) {}

  default void httpFailure(VulcanFailureCategory category) {}

  default void cacheFailure(CacheFailure failure) {}

  default <T> T observeCache(CacheFailure failure, Supplier<T> action) {
    try {
      return action.get();
    } catch (RuntimeException exception) {
      cacheFailure(failure);
      throw exception;
    }
  }

  default <T> T observe(Stage stage, Supplier<T> action) {
    begin(stage);
    T result = action.get();
    pass(stage);
    return result;
  }
}
