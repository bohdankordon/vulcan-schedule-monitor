package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.RememberedCredentials;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanLoginRequest;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private static final byte[] CLEAR = "synthetic clear value".getBytes(StandardCharsets.UTF_8);

  @Test
  void roundTripsWithAad() {
    AesGcmCipher cipher = new AesGcmCipher(VulcanMasterKey.fromBase64(KEY));
    EncryptedPayload encrypted = cipher.encrypt(CLEAR, "account:1:session");

    assertThat(cipher.decrypt(encrypted, "account:1:session")).isEqualTo(CLEAR);
  }

  @Test
  void freshNonceProducesDifferentOutput() {
    AesGcmCipher cipher = new AesGcmCipher(VulcanMasterKey.fromBase64(KEY));

    EncryptedPayload first = cipher.encrypt(CLEAR, "aad");
    EncryptedPayload second = cipher.encrypt(CLEAR, "aad");

    assertThat(first.nonce()).isNotEqualTo(second.nonce());
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
  }

  @Test
  void tamperingWrongKeyAndWrongAadFailClosed() {
    AesGcmCipher cipher = new AesGcmCipher(VulcanMasterKey.fromBase64(KEY));
    EncryptedPayload encrypted = cipher.encrypt(CLEAR, "correct-aad");
    byte[] ciphertext = encrypted.ciphertext();
    ciphertext[0] ^= 1;
    byte[] nonce = encrypted.nonce();
    nonce[0] ^= 1;
    byte[] otherKey = new byte[32];
    otherKey[0] = 1;
    AesGcmCipher wrongCipher =
        new AesGcmCipher(VulcanMasterKey.fromBase64(Base64.getEncoder().encodeToString(otherKey)));

    assertThatThrownBy(
            () ->
                cipher.decrypt(
                    new EncryptedPayload(encrypted.keyVersion(), encrypted.nonce(), ciphertext),
                    "correct-aad"))
        .isInstanceOf(SecretDecryptionException.class);
    assertThatThrownBy(
            () ->
                cipher.decrypt(
                    new EncryptedPayload(encrypted.keyVersion(), nonce, encrypted.ciphertext()),
                    "correct-aad"))
        .isInstanceOf(SecretDecryptionException.class);
    assertThatThrownBy(() -> wrongCipher.decrypt(encrypted, "correct-aad"))
        .isInstanceOf(SecretDecryptionException.class);
    assertThatThrownBy(() -> cipher.decrypt(encrypted, "wrong-aad"))
        .isInstanceOf(SecretDecryptionException.class);
  }

  @Test
  void exactAes256KeyLengthIsRequiredWithoutReflectingInput() {
    String invalid = Base64.getEncoder().encodeToString(new byte[31]);

    assertThatThrownBy(() -> VulcanMasterKey.fromBase64(invalid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining(invalid);
  }

  @Test
  void secretBearingObjectsRedactToString() {
    URI base = URI.create("https://school.vulcan.net.pl/tenant/app/");
    VulcanSessionMaterial material =
        new VulcanSessionMaterial(base, base, "verification-secret", "guid-secret", "name=value");
    VulcanLoginRequest login =
        new VulcanLoginRequest(base, "login-secret", "pass-secret".toCharArray());
    RememberedCredentials credentials =
        new RememberedCredentials(base, "login-secret", "pass-secret".toCharArray());

    assertThat(material.toString())
        .doesNotContain("verification-secret", "guid-secret", "name=value");
    assertThat(login.toString()).doesNotContain("login-secret", "pass-secret");
    assertThat(credentials.toString()).doesNotContain("login-secret", "pass-secret");
    assertThat(VulcanMasterKey.fromBase64(KEY).toString()).doesNotContain(KEY);
  }
}
