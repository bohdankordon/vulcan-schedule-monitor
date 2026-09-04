package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap.SchoolBootstrap;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.util.List;

public final class DefaultVulcanSessionVerifier implements VulcanSessionVerifier {

  @Override
  public VerifiedVulcanSession verifyAndDiscover(VulcanSessionMaterial material) {
    try {
      VulcanSession liveSession = VulcanSession.fromMaterial(material);
      VulcanClient client = new VulcanClient(liveSession);
      SchoolBootstrap cache = client.getCache();
      List<SchoolClass> classes = client.getTree(cache.currentSchoolYear());
      return new VerifiedVulcanSession(liveSession.snapshotMaterial(), classes);
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
