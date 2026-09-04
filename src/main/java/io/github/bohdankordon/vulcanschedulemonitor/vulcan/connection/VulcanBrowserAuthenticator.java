package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;

public interface VulcanBrowserAuthenticator {

  VulcanSessionMaterial authenticate(VulcanLoginRequest request);
}
