package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import com.microsoft.playwright.options.Cookie;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanCookieMaterial;
import java.net.URI;

public final class BrowserCookieObservation {
  private final URI origin;
  private final String name, value, path, domain;
  private final boolean secure, httpOnly;

  /** Compatibility for observations whose attributes are genuinely unavailable. */
  public BrowserCookieObservation(URI origin, String name, String value) {
    this(origin, name, value, null, null, false, false);
  }

  public BrowserCookieObservation(
      URI origin,
      String name,
      String value,
      String path,
      String domain,
      boolean secure,
      boolean httpOnly) {
    this.origin = origin;
    this.name = name;
    this.value = value;
    this.path = path;
    this.domain = domain;
    this.secure = secure;
    this.httpOnly = httpOnly;
  }

  static BrowserCookieObservation fromPlaywright(URI origin, Cookie cookie) {
    if (cookie == null
        || cookie.path == null
        || cookie.domain == null
        || cookie.secure == null
        || cookie.httpOnly == null)
      throw new IllegalArgumentException("Browser cookie metadata is invalid");
    return new BrowserCookieObservation(
        origin,
        cookie.name,
        cookie.value,
        cookie.path,
        cookie.domain,
        cookie.secure,
        cookie.httpOnly);
  }

  URI origin() {
    return origin;
  }

  VulcanCookieMaterial material() {
    return new VulcanCookieMaterial(name, value, path, domain, secure, httpOnly);
  }

  @Override
  public String toString() {
    return "BrowserCookieObservation[cookie=[redacted]]";
  }
}
