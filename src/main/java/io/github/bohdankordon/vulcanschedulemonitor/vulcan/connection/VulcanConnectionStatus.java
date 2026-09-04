package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

public record VulcanConnectionStatus(State state, int activeClassCount) {

  public enum State {
    NOT_CONNECTED,
    CONNECTED,
    RECONNECT_REQUIRED
  }
}
