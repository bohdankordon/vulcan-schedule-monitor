package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

public record ConnectOutcome(Status status, int classCount, boolean retryAllowed) {

  public enum Status {
    SUCCESS,
    TOKEN_INVALID,
    INVALID_PORTAL,
    INVALID_CREDENTIALS,
    UNSUPPORTED_AUTH_FLOW,
    TRANSIENT_FAILURE,
    PROTOCOL_FAILURE
  }

  public static ConnectOutcome success(int classCount) {
    return new ConnectOutcome(Status.SUCCESS, classCount, false);
  }

  public static ConnectOutcome failure(Status status, boolean retryAllowed) {
    return new ConnectOutcome(status, 0, retryAllowed);
  }
}
