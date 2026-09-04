package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmCipher {

  public static final int KEY_VERSION = 1;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final VulcanMasterKey masterKey;
  private final SecureRandom secureRandom;

  public AesGcmCipher(VulcanMasterKey masterKey) {
    this(masterKey, new SecureRandom());
  }

  AesGcmCipher(VulcanMasterKey masterKey, SecureRandom secureRandom) {
    this.masterKey = masterKey;
    this.secureRandom = secureRandom;
  }

  public EncryptedPayload encrypt(byte[] plaintext, String aad) {
    byte[] nonce = new byte[NONCE_BYTES];
    secureRandom.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, masterKey.key(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return new EncryptedPayload(KEY_VERSION, nonce, cipher.doFinal(plaintext));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("VULCAN secret encryption failed", exception);
    }
  }

  public byte[] decrypt(EncryptedPayload payload, String aad) {
    if (payload.keyVersion() != KEY_VERSION || payload.nonce().length != NONCE_BYTES) {
      throw new SecretDecryptionException();
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE, masterKey.key(), new GCMParameterSpec(TAG_BITS, payload.nonce()));
      cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(payload.ciphertext());
    } catch (GeneralSecurityException | RuntimeException exception) {
      throw new SecretDecryptionException();
    }
  }
}
