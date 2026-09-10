package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Finite observation of in-memory cookie mutation across a request. Deliberately never retains or
 * renders cookie names, paths, domains, or values.
 */
public record SessionCookieMutationObservation(
    SessionCookieCountBucket sessionCookiesBefore,
    SessionCookieCountBucket sessionCookiesAfter,
    boolean cookieMaterialChanged,
    int cookieIdentityAddedCount,
    int cookieIdentityRemovedCount,
    int cookieValueChangedCount) {

  private static final int MAX_COUNT = 10;

  public static SessionCookieMutationObservation compare(
      VulcanSessionMaterial before, VulcanSessionMaterial after) {
    if (before == null && after == null) {
      return new SessionCookieMutationObservation(
          SessionCookieCountBucket.ZERO, SessionCookieCountBucket.ZERO, false, 0, 0, 0);
    }
    if (before == null) {
      int count = Math.min(MAX_COUNT, after.cookieCount());
      return new SessionCookieMutationObservation(
          SessionCookieCountBucket.ZERO,
          SessionCookieCountBucket.fromCount(after.cookieCount()),
          true,
          count,
          0,
          0);
    }
    if (after == null) {
      int count = Math.min(MAX_COUNT, before.cookieCount());
      return new SessionCookieMutationObservation(
          SessionCookieCountBucket.fromCount(before.cookieCount()),
          SessionCookieCountBucket.ZERO,
          true,
          0,
          count,
          0);
    }

    boolean materialChanged = !before.sameCookiesAs(after);
    List<VulcanCookieMaterial> beforeCookies = extractCookies(before);
    List<VulcanCookieMaterial> afterCookies = extractCookies(after);

    Map<IdentityKey, VulcanCookieMaterial> beforeMap = toIdentityMap(beforeCookies);
    Map<IdentityKey, VulcanCookieMaterial> afterMap = toIdentityMap(afterCookies);

    int added = 0;
    int removed = 0;
    int valueChanged = 0;

    for (Map.Entry<IdentityKey, VulcanCookieMaterial> entry : afterMap.entrySet()) {
      VulcanCookieMaterial beforeCookie = beforeMap.get(entry.getKey());
      if (beforeCookie == null) {
        added++;
      } else if (!Objects.equals(beforeCookie.value(), entry.getValue().value())) {
        valueChanged++;
      }
    }

    for (IdentityKey key : beforeMap.keySet()) {
      if (!afterMap.containsKey(key)) {
        removed++;
      }
    }

    return new SessionCookieMutationObservation(
        SessionCookieCountBucket.fromCount(before.cookieCount()),
        SessionCookieCountBucket.fromCount(after.cookieCount()),
        materialChanged,
        Math.min(MAX_COUNT, added),
        Math.min(MAX_COUNT, removed),
        Math.min(MAX_COUNT, valueChanged));
  }

  private static List<VulcanCookieMaterial> extractCookies(VulcanSessionMaterial material) {
    if (material.cookieRepresentation() == VulcanSessionMaterial.CookieRepresentation.STRUCTURED) {
      return material.cookies();
    }
    return VulcanSession.fromMaterial(material).snapshotMaterial().cookies();
  }

  private static Map<IdentityKey, VulcanCookieMaterial> toIdentityMap(
      List<VulcanCookieMaterial> cookies) {
    Map<IdentityKey, VulcanCookieMaterial> map = new HashMap<>();
    for (VulcanCookieMaterial cookie : cookies) {
      map.put(
          new IdentityKey(cookie.name().toLowerCase(Locale.ROOT), cookie.domain(), cookie.path()),
          cookie);
    }
    return map;
  }

  private record IdentityKey(String name, String domain, String path) {}

  @Override
  public String toString() {
    return "SessionCookieMutationObservation[before="
        + sessionCookiesBefore
        + ", after="
        + sessionCookiesAfter
        + ", changed="
        + cookieMaterialChanged
        + ", added="
        + cookieIdentityAddedCount
        + ", removed="
        + cookieIdentityRemovedCount
        + ", valueChanged="
        + cookieValueChangedCount
        + "]";
  }
}
