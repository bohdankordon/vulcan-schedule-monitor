package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.RememberedCredentials;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.time.Instant;
import java.util.Optional;

public interface VulcanSecretStore {

  void replace(
      long accountId,
      VulcanSessionMaterial session,
      RememberedCredentials credentials,
      Instant now);

  VulcanSessionMaterial loadSession(long accountId);

  Optional<RememberedCredentials> loadCredentials(long accountId);
}
