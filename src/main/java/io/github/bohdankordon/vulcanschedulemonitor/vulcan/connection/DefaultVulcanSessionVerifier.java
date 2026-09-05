package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap.SchoolBootstrap;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.Stage;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.util.List;

public final class DefaultVulcanSessionVerifier implements VulcanSessionVerifier {
  private final VulcanDiagnostics diagnostics;

  public DefaultVulcanSessionVerifier() {
    this(VulcanDiagnostics.NONE);
  }

  public DefaultVulcanSessionVerifier(VulcanDiagnostics diagnostics) {
    this.diagnostics = java.util.Objects.requireNonNull(diagnostics);
  }

  @Override
  public VerifiedVulcanSession verifyAndDiscover(VulcanSessionMaterial material) {
    try {
      VulcanSession liveSession =
          diagnostics.observe(
              Stage.SESSION_MATERIAL_RECONSTRUCTION, () -> VulcanSession.fromMaterial(material));
      VulcanClient client = new VulcanClient(liveSession, diagnostics);
      SchoolBootstrap cache = client.getCache();
      List<SchoolClass> classes = client.getTree(cache.currentSchoolYear());
      VulcanSessionMaterial verified =
          diagnostics.observe(Stage.SESSION_SNAPSHOT, liveSession::snapshotMaterial);
      return diagnostics.observe(
          Stage.VERIFIED, () -> new VerifiedVulcanSession(verified, classes));
    } catch (VulcanHttpException exception) {
      VulcanAuthFailureCategory category =
          isTransient(exception.category())
              ? VulcanAuthFailureCategory.TRANSIENT
              : VulcanAuthFailureCategory.PROTOCOL_FAILURE;
      throw new VulcanAuthenticationException(category);
    } catch (RuntimeException exception) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    }
  }

  private static boolean isTransient(VulcanFailureCategory category) {
    return category == VulcanFailureCategory.TRANSPORT_ERROR
        || category == VulcanFailureCategory.RATE_LIMITED
        || category == VulcanFailureCategory.SERVER_ERROR;
  }
}
