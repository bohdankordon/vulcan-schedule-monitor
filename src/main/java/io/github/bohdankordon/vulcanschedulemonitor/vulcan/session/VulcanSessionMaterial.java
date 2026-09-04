package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import java.net.URI;
import java.util.Objects;

/** Minimum secret material needed to reconstruct an authenticated VULCAN HTTP session. */
public final class VulcanSessionMaterial {

  private final URI applicationBaseUri;
  private final URI refererUri;
  private final String requestVerificationToken;
  private final String appGuid;
  private final String cookieHeader;

  public VulcanSessionMaterial(
      URI applicationBaseUri,
      URI refererUri,
      String requestVerificationToken,
      String appGuid,
      String cookieHeader) {
    this.applicationBaseUri = normalize(applicationBaseUri);
    this.refererUri = requireSameOrigin(refererUri, this.applicationBaseUri);
    this.requestVerificationToken = requireSecret(requestVerificationToken);
    this.appGuid = requireSecret(appGuid);
    this.cookieHeader = requireSecret(cookieHeader);
  }

  public URI applicationBaseUri() {
    return applicationBaseUri;
  }

  public URI refererUri() {
    return refererUri;
  }

  public String requestVerificationToken() {
    return requestVerificationToken;
  }

  public String appGuid() {
    return appGuid;
  }

  public String cookieHeader() {
    return cookieHeader;
  }

  @Override
  public String toString() {
    return "VulcanSessionMaterial[secrets=[redacted]]";
  }

  private static URI normalize(URI uri) {
    Objects.requireNonNull(uri, "applicationBaseUri must not be null");
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("Application URI is invalid");
    }
    String value = uri.normalize().toASCIIString();
    return URI.create(value.endsWith("/") ? value : value + "/");
  }

  private static URI requireSameOrigin(URI uri, URI base) {
    Objects.requireNonNull(uri, "refererUri must not be null");
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || !uri.getScheme().equalsIgnoreCase(base.getScheme())
        || !uri.getHost().equalsIgnoreCase(base.getHost())
        || uri.getPort() != base.getPort()
        || !uri.getPath().startsWith(base.getPath())) {
      throw new IllegalArgumentException("Referer URI must remain inside the application path");
    }
    return uri;
  }

  private static String requireSecret(String value) {
    if (value == null || value.isBlank() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
      throw new IllegalArgumentException("Required session material is invalid");
    }
    return value;
  }
}
