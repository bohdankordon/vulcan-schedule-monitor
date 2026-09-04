package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap.SchoolBootstrap;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.util.List;

public final class DefaultVulcanSessionVerifier implements VulcanSessionVerifier {

  @Override
  public List<SchoolClass> verifyAndDiscover(VulcanSessionMaterial material) {
    try {
      VulcanClient client = new VulcanClient(VulcanSession.fromMaterial(material));
      SchoolBootstrap cache = client.getCache();
      return client.getTree(cache.currentSchoolYear());
    } catch (RuntimeException exception) {
      throw new VulcanAuthenticationException(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
    }
  }
}
