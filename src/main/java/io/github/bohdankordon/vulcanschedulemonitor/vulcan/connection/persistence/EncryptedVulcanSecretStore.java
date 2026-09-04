package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.RememberedCredentials;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.AesGcmCipher;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.EncryptedPayload;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.SecretDecryptionException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.SecretPayloadCodec;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.VulcanSecretStore;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
class EncryptedVulcanSecretStore implements VulcanSecretStore {

  private final VulcanAccountSecretRepository secrets;
  private final AesGcmCipher cipher;
  private final SecretPayloadCodec codec;

  EncryptedVulcanSecretStore(
      VulcanAccountSecretRepository secrets, AesGcmCipher cipher, SecretPayloadCodec codec) {
    this.secrets = secrets;
    this.cipher = cipher;
    this.codec = codec;
  }

  @Override
  @Transactional
  public void replace(
      long accountId,
      VulcanSessionMaterial session,
      RememberedCredentials credentials,
      Instant now) {
    byte[] sessionBytes = codec.encodeSession(session);
    byte[] credentialBytes = credentials == null ? null : codec.encodeCredentials(credentials);
    try {
      EncryptedPayload encryptedSession = cipher.encrypt(sessionBytes, aad(accountId, "session"));
      EncryptedPayload encryptedCredentials =
          credentialBytes == null
              ? null
              : cipher.encrypt(credentialBytes, aad(accountId, "credentials"));
      VulcanAccountSecretEntity entity = secrets.findById(accountId).orElse(null);
      if (entity == null) {
        entity = new VulcanAccountSecretEntity(accountId);
      } else {
        verifyExistingSecretCanBeDecrypted(accountId, entity);
      }
      entity.replace(
          encryptedSession.keyVersion(),
          encryptedSession.nonce(),
          encryptedSession.ciphertext(),
          encryptedCredentials == null ? null : encryptedCredentials.nonce(),
          encryptedCredentials == null ? null : encryptedCredentials.ciphertext(),
          now);
      secrets.save(entity);
    } finally {
      Arrays.fill(sessionBytes, (byte) 0);
      if (credentialBytes != null) {
        Arrays.fill(credentialBytes, (byte) 0);
      }
    }
  }

  @Override
  @Transactional(readOnly = true)
  public VulcanSessionMaterial loadSession(long accountId) {
    VulcanAccountSecretEntity entity =
        secrets.findById(accountId).orElseThrow(SecretDecryptionException::new);
    byte[] clear =
        cipher.decrypt(
            new EncryptedPayload(
                entity.keyVersion(), entity.sessionNonce(), entity.sessionCiphertext()),
            aad(accountId, "session"));
    try {
      return codec.decodeSession(clear);
    } finally {
      Arrays.fill(clear, (byte) 0);
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<RememberedCredentials> loadCredentials(long accountId) {
    VulcanAccountSecretEntity entity =
        secrets.findById(accountId).orElseThrow(SecretDecryptionException::new);
    if (entity.credentialCiphertext() == null) {
      return Optional.empty();
    }
    byte[] clear =
        cipher.decrypt(
            new EncryptedPayload(
                entity.keyVersion(), entity.credentialNonce(), entity.credentialCiphertext()),
            aad(accountId, "credentials"));
    try {
      return Optional.of(codec.decodeCredentials(clear));
    } finally {
      Arrays.fill(clear, (byte) 0);
    }
  }

  private static String aad(long accountId, String kind) {
    return "vulcan-account:" + accountId + ":" + kind + ":v1";
  }

  private void verifyExistingSecretCanBeDecrypted(
      long accountId, VulcanAccountSecretEntity entity) {
    byte[] clear =
        cipher.decrypt(
            new EncryptedPayload(
                entity.keyVersion(), entity.sessionNonce(), entity.sessionCiphertext()),
            aad(accountId, "session"));
    try {
      codec.decodeSession(clear);
    } finally {
      Arrays.fill(clear, (byte) 0);
    }
  }
}
