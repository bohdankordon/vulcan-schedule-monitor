package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import java.net.URI;

public final class BrowserCookieObservation {

  private final URI origin;
  private final String name;
  private final String value;

  public BrowserCookieObservation(URI origin, String name, String value) {
    this.origin = origin;
    this.name = name;
    this.value = value;
  }

  URI origin() {
    return origin;
  }

  String headerPair() {
    return name + "=" + value;
  }

  @Override
  public String toString() {
    return "BrowserCookieObservation[cookie=[redacted]]";
  }
}
