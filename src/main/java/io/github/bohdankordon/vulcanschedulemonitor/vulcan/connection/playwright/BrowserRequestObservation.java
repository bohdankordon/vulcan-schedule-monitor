package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import java.net.URI;
import java.util.Objects;

public final class BrowserRequestObservation {

  private final URI uri;
  private final String referer;
  private final String requestVerificationToken;
  private final String appGuid;

  public BrowserRequestObservation(
      URI uri, String referer, String requestVerificationToken, String appGuid) {
    this.uri = Objects.requireNonNull(uri, "uri must not be null");
    this.referer = referer;
    this.requestVerificationToken = requestVerificationToken;
    this.appGuid = appGuid;
  }

  URI uri() {
    return uri;
  }

  String referer() {
    return referer;
  }

  String requestVerificationToken() {
    return requestVerificationToken;
  }

  String appGuid() {
    return appGuid;
  }

  boolean isComplete() {
    return !isBlank(referer) && !isBlank(requestVerificationToken) && !isBlank(appGuid);
  }

  @Override
  public String toString() {
    return "BrowserRequestObservation[request=[redacted]]";
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
