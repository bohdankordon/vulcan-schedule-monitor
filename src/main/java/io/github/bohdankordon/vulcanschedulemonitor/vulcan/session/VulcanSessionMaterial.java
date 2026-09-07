package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

/** Minimum secret material needed to reconstruct an authenticated VULCAN HTTP session. */
public final class VulcanSessionMaterial {

  private final URI applicationBaseUri;
  private final URI refererUri;
  private final String requestVerificationToken;
  private final String appGuid;

  public enum CookieRepresentation {
    LEGACY_HEADER,
    STRUCTURED
  }

  private final String cookieHeader;
  private final List<VulcanCookieMaterial> cookies;

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
    this.cookies = null;
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

  private VulcanSessionMaterial(
      URI base, URI referer, String token, String guid, List<VulcanCookieMaterial> cookies) {
    this.applicationBaseUri = normalize(base);
    this.refererUri = requireSameOrigin(referer, this.applicationBaseUri);
    this.requestVerificationToken = requireSecret(token);
    this.appGuid = requireSecret(guid);
    if (cookies == null || cookies.isEmpty() || cookies.size() > VulcanCookieMaterial.MAX_COOKIES)
      throw VulcanCookieMaterial.invalid();
    Set<HttpCookieIdentity> identities = new HashSet<>();
    for (VulcanCookieMaterial cookie : cookies) {
      if (cookie == null
          || !identities.add(
              new HttpCookieIdentity(
                  cookie.name().toLowerCase(Locale.ROOT), cookie.domain(), cookie.path())))
        throw VulcanCookieMaterial.invalid();
    }
    this.cookies = List.copyOf(cookies);
    this.cookieHeader = null;
  }

  public static VulcanSessionMaterial structured(
      URI base, URI referer, String token, String guid, List<VulcanCookieMaterial> cookies) {
    return new VulcanSessionMaterial(base, referer, token, guid, cookies);
  }

  // This ephemeral key must never render secret identity fields.
  private record HttpCookieIdentity(String name, String domain, String path) {
    @Override
    public String toString() {
      return "CookieIdentity[redacted]";
    }
  }

  public CookieRepresentation cookieRepresentation() {
    return cookies == null ? CookieRepresentation.LEGACY_HEADER : CookieRepresentation.STRUCTURED;
  }

  public List<VulcanCookieMaterial> cookies() {
    if (cookies == null) throw new IllegalStateException("Structured cookies unavailable");
    return cookies;
  }

  /** VSM1 compatibility only. Modern callers must use cookies(). */
  public String legacyCookieHeader() {
    if (cookies != null) throw new IllegalStateException("Legacy cookie header unavailable");
    return cookieHeader;
  }

  /**
   * Secret-bearing, lossy rendering for tests/diagnostics only. Never persistence or routing
   * authority.
   */
  public String cookiePairsForDiagnostics() {
    return cookies == null
        ? cookieHeader
        : cookies.stream()
            .map(cookie -> cookie.name() + "=" + cookie.value())
            .collect(Collectors.joining("; "));
  }

  public int cookieCount() {
    return cookies == null ? cookieHeader.split(";", -1).length : cookies.size();
  }

  /** Order-independent secret comparison; modern equality includes every retained attribute. */
  public boolean sameCookiesAs(VulcanSessionMaterial other) {
    if (cookies != null && other.cookies != null) {
      return cookies.size() == other.cookies.size()
          && new HashSet<>(cookies).equals(new HashSet<>(other.cookies));
    }
    if (cookies == null && other.cookies == null) {
      return Arrays.stream(cookieHeader.split(";", -1))
          .map(String::trim)
          .sorted()
          .toList()
          .equals(
              Arrays.stream(other.cookieHeader.split(";", -1)).map(String::trim).sorted().toList());
    }
    if (cookieCount() != other.cookieCount()) return false;
    // Legacy has no original attributes. Compare its effective JDK reconstruction, not invented
    // metadata.
    return VulcanSession.fromMaterial(this)
        .snapshotMaterial()
        .sameCookiesAs(VulcanSession.fromMaterial(other).snapshotMaterial());
  }

  public enum UriValidationFailure {
    NONE,
    APPLICATION_BASE,
    REFERER
  }

  /**
   * Read-only diagnostic for an already rejected candidate. Reuses the exact constructor URI
   * checks; NONE says nothing about the remaining secret material or authentication success.
   */
  public static UriValidationFailure diagnoseUriValidation(URI applicationBase, URI referer) {
    URI base;
    try {
      base = normalize(applicationBase);
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return UriValidationFailure.APPLICATION_BASE;
    }
    try {
      requireSameOrigin(referer, base);
    } catch (IllegalArgumentException | NullPointerException ignored) {
      return UriValidationFailure.REFERER;
    }
    return UriValidationFailure.NONE;
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
