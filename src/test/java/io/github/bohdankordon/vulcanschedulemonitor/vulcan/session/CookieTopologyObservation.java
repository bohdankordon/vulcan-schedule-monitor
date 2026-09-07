package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import java.net.HttpCookie;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Only finite facts escape this boundary. Counts saturate at 1000 (meaning 1000 or more). */
record CookieTopologyObservation(
    int totalCookieCount,
    boolean duplicateNamePresent,
    boolean duplicateNameDifferentPathPresent,
    boolean duplicateNameDifferentDomainPresent) {
  static CookieTopologyObservation observe(List<HttpCookie> cookies) {
    // Names/domains/paths are ephemeral comparison keys, never fields of the observation.
    Map<String, HttpCookie> firstByName = new HashMap<>();
    boolean duplicate = false;
    boolean differentPath = false;
    boolean differentDomain = false;
    for (HttpCookie cookie : cookies) {
      HttpCookie first = firstByName.putIfAbsent(cookie.getName().toLowerCase(Locale.ROOT), cookie);
      if (first != null) {
        duplicate = true;
        differentPath |= !Objects.equals(first.getPath(), cookie.getPath());
        differentDomain |= !Objects.equals(domain(first), domain(cookie));
      }
    }
    return new CookieTopologyObservation(
        Math.min(cookies.size(), 1000), duplicate, differentPath, differentDomain);
  }

  private static String domain(HttpCookie cookie) {
    return cookie.getDomain() == null ? null : cookie.getDomain().toLowerCase(Locale.ROOT);
  }
}
