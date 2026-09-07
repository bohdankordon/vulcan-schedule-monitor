package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.util.*;
import java.util.function.Supplier;

/** Local observation only. Secret pairs never enter the report or an exception. */
final class NativeSessionCookieObservation {
  static void observe(
      Supplier<VulcanSessionMaterial> snapshot,
      NativeSessionBaselineReport report,
      Runnable request) {
    var before = capture(snapshot);
    if (before != null) report.put("session.cookieCountBefore", before.cookieCount());
    boolean responseObserved = false;
    try {
      request.run();
      responseObserved = true;
    } catch (VulcanHttpException failure) {
      responseObserved = failure.category() != VulcanFailureCategory.TRANSPORT_ERROR;
      throw failure;
    } catch (VulcanProtocolException failure) {
      responseObserved = true;
      throw failure;
    } finally {
      // Snapshot even when the transport throws. No socket, retry, or persistence here.
      var after = capture(snapshot);
      // Transport errors do not establish dispatch. Keep post fields unavailable
      // rather than turning an unchanged local store into evidence of no rotation.
      if (responseObserved && before != null && after != null) {
        report.put("session.cookieCountAfter", after.cookieCount());
        report.put("session.cookieCountChanged", before.cookieCount() != after.cookieCount());
        report.put("session.cookieMaterialChanged", !before.sameCookiesAs(after));
      }
    }
  }

  private static VulcanSessionMaterial capture(Supplier<VulcanSessionMaterial> snapshot) {
    try {
      var material = snapshot.get();
      if (material.cookieCount() > 1000) return null;
      if (material.cookieRepresentation()
              == VulcanSessionMaterial.CookieRepresentation.LEGACY_HEADER
          && Arrays.stream(material.legacyCookieHeader().split(";", -1))
              .anyMatch(pair -> pair.trim().indexOf('=') <= 0)) return null;
      return material;
    } catch (RuntimeException ignored) {
      return null;
    }
  }
}
