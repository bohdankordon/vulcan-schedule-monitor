package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class VulcanAccountSecretEntityTest {

  @Test
  void replaceSessionWithMatchingKeyVersionPreservesCredentials() {
    VulcanAccountSecretEntity entity = new VulcanAccountSecretEntity(1L);
    Instant t1 = Instant.parse("2026-09-01T10:00:00Z");
    byte[] sessionNonce = new byte[] {1, 2, 3};
    byte[] sessionCiphertext = new byte[] {4, 5, 6};
    byte[] credentialNonce = new byte[] {7, 8, 9};
    byte[] credentialCiphertext = new byte[] {10, 11, 12};

    entity.replace(1, sessionNonce, sessionCiphertext, credentialNonce, credentialCiphertext, t1);
    assertThat(entity.keyVersion()).isEqualTo(1);
    assertThat(entity.credentialNonce()).isEqualTo(credentialNonce);
    assertThat(entity.credentialCiphertext()).isEqualTo(credentialCiphertext);

    Instant t2 = Instant.parse("2026-09-01T11:00:00Z");
    byte[] newSessionNonce = new byte[] {21, 22, 23};
    byte[] newSessionCiphertext = new byte[] {24, 25, 26};

    entity.replaceSession(1, newSessionNonce, newSessionCiphertext, t2);

    assertThat(entity.keyVersion()).isEqualTo(1);
    assertThat(entity.sessionNonce()).isEqualTo(newSessionNonce);
    assertThat(entity.sessionCiphertext()).isEqualTo(newSessionCiphertext);
    assertThat(entity.credentialNonce()).isEqualTo(credentialNonce);
    assertThat(entity.credentialCiphertext()).isEqualTo(credentialCiphertext);
  }

  @Test
  void replaceSessionWithDivergentKeyVersionThrowsWhenCredentialsExist() {
    VulcanAccountSecretEntity entity = new VulcanAccountSecretEntity(1L);
    Instant t1 = Instant.parse("2026-09-01T10:00:00Z");

    entity.replace(1, new byte[] {1}, new byte[] {2}, new byte[] {3}, new byte[] {4}, t1);

    Instant t2 = Instant.parse("2026-09-01T11:00:00Z");
    assertThatThrownBy(() -> entity.replaceSession(2, new byte[] {5}, new byte[] {6}, t2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(
            "Cannot update session key_version to 2 while credentials exist with key_version 1");
  }

  @Test
  void replaceSessionWithDivergentKeyVersionSucceedsWhenNoCredentials() {
    VulcanAccountSecretEntity entity = new VulcanAccountSecretEntity(1L);
    Instant t1 = Instant.parse("2026-09-01T10:00:00Z");

    entity.replace(1, new byte[] {1}, new byte[] {2}, null, null, t1);

    Instant t2 = Instant.parse("2026-09-01T11:00:00Z");
    entity.replaceSession(2, new byte[] {5}, new byte[] {6}, t2);

    assertThat(entity.keyVersion()).isEqualTo(2);
    assertThat(entity.credentialCiphertext()).isNull();
    assertThat(entity.credentialNonce()).isNull();
  }

  @Test
  void toStringRedactsSecretContent() {
    VulcanAccountSecretEntity entity = new VulcanAccountSecretEntity(1L);
    assertThat(entity.toString()).isEqualTo("VulcanAccountSecretEntity[value=[redacted]]");
  }
}
