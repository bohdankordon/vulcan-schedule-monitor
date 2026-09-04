package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token;

public final class RawConnectToken {

  private final String value;

  public RawConnectToken(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("Connection token is required");
    }
    this.value = value;
  }

  public String value() {
    return value;
  }

  @Override
  public String toString() {
    return "RawConnectToken[value=[redacted]]";
  }
}
