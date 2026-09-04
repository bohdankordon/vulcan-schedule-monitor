package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;

/** An already authenticated, isolated VULCAN browser session. */
public final class VulcanSession {

  private static final String REDACTED = "[redacted]";

  private final URI applicationBaseUri;
  private final URI refererUri;
  private final URI origin;
  private final SecretValue requestVerificationToken;
  private final SecretValue appGuid;
  private final CookieManager cookieManager;

  private VulcanSession(
      URI applicationBaseUri,
      String requestVerificationToken,
      String appGuid,
      String browserCookieHeader,
      URI refererUri) {
    this.applicationBaseUri = normalizeApplicationUri(applicationBaseUri);
    this.origin = toOrigin(this.applicationBaseUri);
    this.refererUri = requireSameOrigin(refererUri, this.origin, "Referer URI");
    this.requestVerificationToken =
        new SecretValue(requireHeaderValue(requestVerificationToken, "verification token"));
    this.appGuid = new SecretValue(requireHeaderValue(appGuid, "application identifier"));
    this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    seedCookies(requireHeaderValue(browserCookieHeader, "cookie header"));
  }

  public static VulcanSession fromBrowserSession(
      URI applicationBaseUri,
      String requestVerificationToken,
      String appGuid,
      String browserCookieHeader,
      URI refererUri) {
    return new VulcanSession(
        applicationBaseUri, requestVerificationToken, appGuid, browserCookieHeader, refererUri);
  }

  public static VulcanSession fromMaterial(VulcanSessionMaterial material) {
    Objects.requireNonNull(material, "material must not be null");
    return new VulcanSession(
        material.applicationBaseUri(),
        material.requestVerificationToken(),
        material.appGuid(),
        material.cookieHeader(),
        material.refererUri());
  }

  /** Captures current cookies too, including rotations received by the HTTP client. */
  public VulcanSessionMaterial snapshotMaterial() {
    String cookieHeader =
        cookieManager.getCookieStore().getCookies().stream()
            .map(cookie -> cookie.getName() + "=" + cookie.getValue())
            .collect(Collectors.joining("; "));
    return new VulcanSessionMaterial(
        applicationBaseUri,
        refererUri,
        requestVerificationToken.value(),
        appGuid.value(),
        cookieHeader);
  }

  public static VulcanSession fromBrowserSession(
      URI applicationBaseUri,
      String requestVerificationToken,
      String appGuid,
      String browserCookieHeader) {
    return fromBrowserSession(
        applicationBaseUri,
        requestVerificationToken,
        appGuid,
        browserCookieHeader,
        applicationBaseUri);
  }

  public URI applicationBaseUri() {
    return applicationBaseUri;
  }

  public URI resolve(String endpointPath) {
    Objects.requireNonNull(endpointPath, "endpointPath must not be null");
    URI endpoint = URI.create(endpointPath);
    if (endpoint.isAbsolute()
        || endpoint.getRawAuthority() != null
        || endpoint.getRawQuery() != null
        || endpoint.getRawFragment() != null
        || endpointPath.startsWith("/")) {
      throw new IllegalArgumentException("Endpoint path must be relative to the application URI");
    }
    return validateRequestUri(applicationBaseUri.resolve(endpoint));
  }

  public URI validateRequestUri(URI uri) {
    URI validated = requireSameOrigin(uri, origin, "Request URI");
    if (!validated.getPath().startsWith(applicationBaseUri.getPath())) {
      throw new IllegalArgumentException("Request URI must remain inside the application path");
    }
    return validated;
  }

  public String origin() {
    return origin.toASCIIString();
  }

  public HttpClient.Builder configure(HttpClient.Builder builder) {
    return Objects.requireNonNull(builder, "builder must not be null").cookieHandler(cookieManager);
  }

  public void applyCommonHeaders(HttpHeaders headers) {
    Objects.requireNonNull(headers, "headers must not be null");
    headers.set("X-V-RequestVerificationToken", requestVerificationToken.value());
    headers.set("X-V-AppGuid", appGuid.value());
    headers.set("X-Requested-With", "XMLHttpRequest");
    headers.set(HttpHeaders.REFERER, refererUri.toASCIIString());
  }

  @Override
  public String toString() {
    return "VulcanSession[credentials=" + REDACTED + "]";
  }

  private void seedCookies(String cookieHeader) {
    String[] cookiePairs = cookieHeader.split(";", -1);
    for (String cookiePair : cookiePairs) {
      String candidate = cookiePair.trim();
      int separator = candidate.indexOf('=');
      if (separator <= 0) {
        throw malformedCookieHeader();
      }

      String name = candidate.substring(0, separator).trim();
      String value = candidate.substring(separator + 1).trim();
      if (name.isEmpty() || containsLineBreak(value)) {
        throw malformedCookieHeader();
      }

      try {
        new HttpCookie(name, value);
        cookieManager.put(
            applicationBaseUri,
            Map.of(
                HttpHeaders.SET_COOKIE,
                List.of(candidate + "; Path=" + applicationBaseUri.getRawPath())));
      } catch (IllegalArgumentException | IOException exception) {
        throw malformedCookieHeader();
      }
    }
  }

  private static IllegalArgumentException malformedCookieHeader() {
    return new IllegalArgumentException("Browser cookie header is malformed");
  }

  private static URI normalizeApplicationUri(URI uri) {
    URI validated = requireHttpUri(uri, "application URI");
    if (validated.getQuery() != null || validated.getFragment() != null) {
      throw new IllegalArgumentException("Application URI must not contain a query or fragment");
    }
    if (validated.toString().endsWith("/")) {
      return validated;
    }
    return URI.create(validated.toString() + "/");
  }

  private static URI requireHttpUri(URI uri, String label) {
    Objects.requireNonNull(uri, label + " must not be null");
    if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null) {
      throw new IllegalArgumentException(label + " must be an absolute HTTP URI without user info");
    }
    return uri;
  }

  private static URI toOrigin(URI applicationUri) {
    try {
      return new URI(
          applicationUri.getScheme(),
          null,
          applicationUri.getHost(),
          applicationUri.getPort(),
          null,
          null,
          null);
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("Application URI does not have a valid origin");
    }
  }

  private static URI requireSameOrigin(URI uri, URI expectedOrigin, String label) {
    URI validated = requireHttpUri(uri, label);
    if (!validated.getScheme().equalsIgnoreCase(expectedOrigin.getScheme())
        || !validated.getHost().equalsIgnoreCase(expectedOrigin.getHost())
        || validated.getPort() != expectedOrigin.getPort()) {
      throw new IllegalArgumentException(label + " must use the application origin");
    }
    return validated;
  }

  private static String requireHeaderValue(String value, String label) {
    if (value == null || value.isBlank() || containsLineBreak(value)) {
      throw new IllegalArgumentException(label + " must be present and valid");
    }
    return value;
  }

  private static boolean containsLineBreak(String value) {
    return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
  }

  private static final class SecretValue {

    private final String value;

    private SecretValue(String value) {
      this.value = value;
    }

    private String value() {
      return value;
    }

    @Override
    public String toString() {
      return REDACTED;
    }
  }
}
