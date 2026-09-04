package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.util.List;

public interface VulcanSessionVerifier {

  List<SchoolClass> verifyAndDiscover(VulcanSessionMaterial material);
}
