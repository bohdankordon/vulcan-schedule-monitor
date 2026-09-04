package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token;

public record ConnectTokenValidation(ConnectTokenState state, long appUserId) {

  public static ConnectTokenValidation invalid() {
    return new ConnectTokenValidation(ConnectTokenState.INVALID_OR_EXPIRED, 0);
  }

  public static ConnectTokenValidation valid(long appUserId) {
    return new ConnectTokenValidation(ConnectTokenState.VALID, appUserId);
  }
}
