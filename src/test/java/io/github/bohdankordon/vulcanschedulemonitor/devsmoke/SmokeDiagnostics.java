package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import java.io.PrintStream;
import java.util.EnumMap;

/**
 * Finite output protocol. No input strings, exception messages, or provider values can enter it.
 */
final class SmokeDiagnostics implements VulcanDiagnostics {
  enum State {
    PASS,
    FAIL,
    INCOMPLETE,
    NOT_REACHED
  }

  enum Category {
    SUCCESS,
    INVALID_INPUT,
    INVALID_CREDENTIALS,
    MFA_REQUIRED,
    CAPTCHA_REQUIRED,
    UNSUPPORTED_AUTH_FLOW,
    SESSION_AUTHENTICATION,
    TRANSIENT,
    PROTOCOL_FAILURE,
    HARNESS_FAILURE
  }

  record Response(StatusFamily status, ContentFamily content) {}

  private final EnumMap<Stage, State> stages = new EnumMap<>(Stage.class);
  private final EnumMap<Stage, Response> responses = new EnumMap<>(Stage.class);
  private Stage active = Stage.PORTAL_VALIDATION;
  private VulcanFailureCategory httpFailure;
  private CacheFailure cacheFailure;
  private Category category = Category.HARNESS_FAILURE;
  private Integer classCount;

  @Override
  public void begin(Stage stage) {
    active = stage;
    stages.put(stage, State.INCOMPLETE);
  }

  @Override
  public void pass(Stage stage) {
    stages.put(stage, State.PASS);
  }

  @Override
  public void response(Stage request, StatusFamily status, ContentFamily content) {
    responses.put(request, new Response(status, content));
  }

  @Override
  public void httpFailure(VulcanFailureCategory failure) {
    httpFailure = failure;
  }

  @Override
  public void cacheFailure(CacheFailure failure) {
    cacheFailure = failure;
  }

  void failed(VulcanAuthFailureCategory failure) {
    boolean sessionAuth =
        httpFailure == VulcanFailureCategory.AUTHENTICATION_REQUIRED
            || httpFailure == VulcanFailureCategory.SESSION_REDIRECT
            || httpFailure == VulcanFailureCategory.UNEXPECTED_HTML;
    failed(sessionAuth ? Category.SESSION_AUTHENTICATION : Category.valueOf(failure.name()));
  }

  void failed(Category failure) {
    category = failure;
    stages.put(active, State.FAIL);
  }

  void success(int count) {
    category = Category.SUCCESS;
    classCount = count;
  }

  void print(PrintStream output) {
    output.println("REAL VULCAN SMOKE - LOCAL DEVELOPMENT ONLY");
    for (Stage stage : Stage.values())
      output.println("stage." + stage + "=" + stages.getOrDefault(stage, State.NOT_REACHED));
    responses.forEach(
        (stage, response) ->
            output.println(
                "http."
                    + stage
                    + "="
                    + response.status()
                    + ","
                    + response.content()
                    + ","
                    + (response.status() == StatusFamily.REDIRECT)));
    if (httpFailure != null) output.println("httpFailure=" + httpFailure);
    if (cacheFailure != null) output.println("cacheFailure=" + cacheFailure);
    if (classCount != null) output.println("classCount=" + classCount);
    output.println("category=" + category);
    output.println("result=" + (category == Category.SUCCESS ? "SUCCESS" : "FAIL"));
  }
}
