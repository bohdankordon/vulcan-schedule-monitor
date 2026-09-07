package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import java.util.Arrays;
import java.util.List;

/** Test-source adapter: secret comparisons remain local; only booleans/bounded counts escape. */
public final class SessionFidelityDiagnostics {
  private SessionFidelityDiagnostics() {}

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
    var before = pairs(expected.cookieHeader());
    var after = pairs(actual.cookieHeader());
    return new MaterialComparison(
        expected.applicationBaseUri().equals(actual.applicationBaseUri()),
        expected.refererUri().equals(actual.refererUri()),
        expected.requestVerificationToken().equals(actual.requestVerificationToken()),
        expected.appGuid().equals(actual.appGuid()),
        before.equals(after),
        Math.min(before.size(), 1000),
        Math.min(after.size(), 1000),
        before.size() != after.size());
  }

  public static CookieTopology topology(VulcanSession session) {
    var observation = session.cookieTopologyForDiagnostics();
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

  private static List<String> pairs(String header) {
    // Same multiset semantics as the original M-sequence invariant: ordering alone is ignored.
    return Arrays.stream(header.split(";", -1)).map(String::trim).sorted().toList();
  }
}
