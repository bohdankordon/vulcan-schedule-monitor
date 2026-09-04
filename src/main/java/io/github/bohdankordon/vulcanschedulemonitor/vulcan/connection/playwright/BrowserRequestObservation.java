package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class BrowserRequestObservation {

  private final URI uri;
  private final Map<String, String> headers;

  public BrowserRequestObservation(URI uri, Map<String, String> headers) {
    this.uri = uri;
    TreeMap<String, String> normalized = new TreeMap<>();
    headers.forEach((name, value) -> normalized.put(name.toLowerCase(Locale.ROOT), value));
    this.headers = Map.copyOf(normalized);
  }

  URI uri() {
    return uri;
  }

  String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }

  @Override
  public String toString() {
    return "BrowserRequestObservation[request=[redacted]]";
  }
}
