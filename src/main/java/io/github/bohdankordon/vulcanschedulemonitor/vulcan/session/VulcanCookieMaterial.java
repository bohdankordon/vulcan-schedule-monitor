package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import java.net.HttpCookie;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Immutable secret cookie state; deliberately excludes relative lifetime and JDK internals. */
public record VulcanCookieMaterial(
    String name, String value, String path, String domain, boolean secure, boolean httpOnly) {
  public static final int MAX_COOKIES = 1000;
  public static final int MAX_NAME_BYTES = 256;
  public static final int MAX_VALUE_BYTES = 16384;
  public static final int MAX_PATH_BYTES = 4096;
  public static final int MAX_DOMAIN_BYTES = 253;

  public VulcanCookieMaterial {
    requireField(name, MAX_NAME_BYTES);
    requireField(value, MAX_VALUE_BYTES);
    if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.indexOf(';') >= 0) throw invalid();
    try {
      new HttpCookie(name, value);
    } catch (IllegalArgumentException ignored) {
      throw invalid();
    }
    if (path != null) {
      requireField(path, MAX_PATH_BYTES);
      if (!path.startsWith("/") || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0)
        throw invalid();
    }
    if (domain != null) {
      requireField(domain, MAX_DOMAIN_BYTES);
      String host = domain.startsWith(".") ? domain.substring(1) : domain;
      if (host.isEmpty()) throw invalid();
      for (String label : host.split("\\.", -1)) {
        if (!label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")) throw invalid();
      }
      domain = domain.toLowerCase(Locale.ROOT);
    }
  }

  static VulcanCookieMaterial fromCookie(HttpCookie cookie) {
    return new VulcanCookieMaterial(
        cookie.getName(),
        cookie.getValue(),
        cookie.getPath(),
        cookie.getDomain(),
        cookie.getSecure(),
        cookie.isHttpOnly());
  }

  HttpCookie toCookie() {
    HttpCookie cookie = new HttpCookie(name, value);
    // Match the existing Set-Cookie/Cookie header version, without RFC2965 quoting/metadata.
    cookie.setVersion(0);
    cookie.setPath(path);
    cookie.setDomain(domain);
    cookie.setSecure(secure);
    cookie.setHttpOnly(httpOnly);
    // Same effective persistence lifetime as V1: no relative Max-Age is restarted on reload.
    cookie.setMaxAge(-1);
    return cookie;
  }

  private static void requireField(String value, int maxBytes) {
    if (value == null
        || value.length() > maxBytes
        || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
        || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
        || value.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
  }

  static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Cookie material is invalid");
  }

  @Override
  public String toString() {
    return "VulcanCookieMaterial[redacted]";
  }
}
