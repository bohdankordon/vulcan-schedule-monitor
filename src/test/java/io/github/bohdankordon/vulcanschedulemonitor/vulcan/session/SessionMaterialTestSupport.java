package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

/**
 * Synthetic test helpers; comparison/topology observations contain only booleans/bounded counts.
 */
public final class SessionMaterialTestSupport {
  private SessionMaterialTestSupport() {}

  /** Lossy rendering for synthetic assertions only; no production caller or logging. */
  public static String cookiePairs(VulcanSessionMaterial material) {
    if (material.cookieRepresentation() == VulcanSessionMaterial.CookieRepresentation.LEGACY_HEADER)
      return material.legacyCookieHeader();
    return material.cookies().stream()
        .map(cookie -> cookie.name() + "=" + cookie.value())
        .collect(java.util.stream.Collectors.joining("; "));
  }

  public record MaterialComparison(
      boolean applicationBaseSame,
      boolean refererSame,
      boolean verificationTokenSame,
      boolean appGuidSame,
      boolean cookieMaterialSame,
      int expectedCookieCount,
      int actualCookieCount,
      boolean cookieCountChanged) {
    public boolean allSame() {
      return applicationBaseSame
          && refererSame
          && verificationTokenSame
          && appGuidSame
          && cookieMaterialSame;
    }
  }

  public record CookieTopology(
      int totalCookieCount,
      boolean duplicateNamePresent,
      boolean duplicateNameDifferentPathPresent,
      boolean duplicateNameDifferentDomainPresent) {}

  public record MaterialRoundTrip(
      boolean cookieMaterialSame, int cookieCountBefore, int cookieCountAfter) {}

  public static MaterialComparison compare(
      VulcanSessionMaterial expected, VulcanSessionMaterial actual) {
    int before = expected.cookieCount();
    int after = actual.cookieCount();
    return new MaterialComparison(
        expected.applicationBaseUri().equals(actual.applicationBaseUri()),
        expected.refererUri().equals(actual.refererUri()),
        expected.requestVerificationToken().equals(actual.requestVerificationToken()),
        expected.appGuid().equals(actual.appGuid()),
        expected.sameCookiesAs(actual),
        Math.min(before, 1000),
        Math.min(after, 1000),
        before != after);
  }

  public static CookieTopology topology(VulcanSession session) {
    var observation =
        CookieTopologyObservation.observe(
            session.snapshotMaterial().cookies().stream()
                .map(VulcanCookieMaterial::toCookie)
                .toList());
    return new CookieTopology(
        observation.totalCookieCount(),
        observation.duplicateNamePresent(),
        observation.duplicateNameDifferentPathPresent(),
        observation.duplicateNameDifferentDomainPresent());
  }

  public static MaterialRoundTrip roundTrip(VulcanSessionMaterial material) {
    var comparison = compare(material, VulcanSession.fromMaterial(material).snapshotMaterial());
    return new MaterialRoundTrip(
        comparison.cookieMaterialSame(),
        comparison.expectedCookieCount(),
        comparison.actualCookieCount());
  }
}
