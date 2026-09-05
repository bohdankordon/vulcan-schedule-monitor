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
    if (before != null) report.put("session.cookieCountBefore", before.pairs.size());
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
        report.put("session.cookieCountAfter", after.pairs.size());
        report.put("session.cookieCountChanged", before.pairs.size() != after.pairs.size());
        report.put("session.cookieMaterialChanged", !before.pairs.equals(after.pairs));
      }
    }
  }

  private static CookiePairs capture(Supplier<VulcanSessionMaterial> snapshot) {
    try {
      var material = snapshot.get();
      var pairs = new ArrayList<String>();
      for (String pair : material.cookieHeader().split(";", -1)) {
        String normalized = pair.trim();
        if (normalized.indexOf('=') <= 0 || pairs.size() == 1000) return null;
        pairs.add(normalized);
      }
      // Multiset comparison: preserves duplicate pairs but ignores their ordering.
      Collections.sort(pairs);
      return new CookiePairs(pairs);
    } catch (RuntimeException ignored) {
      // A local snapshot/parse failure must not obscure the schedule outcome.
      return null;
    }
  }

  private static final class CookiePairs {
    private final List<String> pairs;

    private CookiePairs(List<String> pairs) {
      this.pairs = pairs;
    }

    @Override
    public String toString() {
      return "CookiePairs[redacted]";
    }
  }
}
