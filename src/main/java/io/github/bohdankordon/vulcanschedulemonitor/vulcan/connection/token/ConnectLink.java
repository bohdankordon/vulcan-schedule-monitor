package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token;

public final class ConnectLink {

  private final boolean enabled;
  private final String url;

  private ConnectLink(boolean enabled, String url) {
    this.enabled = enabled;
    this.url = url;
  }

  public static ConnectLink disabled() {
    return new ConnectLink(false, null);
  }

  public static ConnectLink enabled(String url) {
    return new ConnectLink(true, url);
  }

  public boolean enabled() {
    return enabled;
  }

  public String url() {
    return url;
  }

  @Override
  public String toString() {
    return "ConnectLink[url=[redacted]]";
  }
}
