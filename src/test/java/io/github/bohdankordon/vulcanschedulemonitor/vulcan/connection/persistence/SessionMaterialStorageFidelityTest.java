package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionMaterialTestSupport.cookiePairs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionMaterialStorageFidelityTest {
  @org.junit.jupiter.api.Test
  void structuredV2SurvivesEncryptionExistingRowReplacementAndReconstruction() {
    URI base = URI.create("https://secret-cookie-domain.invalid/SECRET_COOKIE_PATH/");
    var material =
        VulcanSessionMaterial.structured(
            base,
            base,
            "SUPER_SECRET_TOKEN",
            "SUPER_SECRET_APPGUID",
            List.of(
                new VulcanCookieMaterial(
                    "SUPER_SECRET_COOKIE_NAME",
                    "SUPER_SECRET_COOKIE_VALUE",
                    "/",
                    ".secret-cookie-domain.invalid",
                    true,
                    true),
                new VulcanCookieMaterial(
                    "SUPER_SECRET_COOKIE_NAME",
                    "other",
                    "/SECRET_COOKIE_PATH/",
                    "secret-cookie-domain.invalid",
                    false,
                    false)));
    var codec = new SecretPayloadCodec();
    var repository = mock(VulcanAccountSecretRepository.class);
    var row = new AtomicReference<VulcanAccountSecretEntity>();
    when(repository.findById(1L)).thenAnswer(i -> Optional.ofNullable(row.get()));
    when(repository.save(any()))
        .thenAnswer(
            i -> {
              row.set(i.getArgument(0));
              return row.get();
            });
    var store =
        new EncryptedVulcanSecretStore(
            repository,
            new AesGcmCipher(
                VulcanMasterKey.fromBase64(Base64.getEncoder().encodeToString(new byte[32]))),
            codec);
    for (int i = 0; i < 2; i++) {
      store.replace(1, material, null, Instant.EPOCH.plusSeconds(i));
      var loaded = store.loadSession(1);
      assertThat(loaded.cookieRepresentation())
          .isEqualTo(VulcanSessionMaterial.CookieRepresentation.STRUCTURED);
      assertThat(Arrays.equals(codec.encodeSession(material), codec.encodeSession(loaded)))
          .isTrue();
      assertThat(
              SessionMaterialTestSupport.compare(
                      material, VulcanSession.fromMaterial(loaded).snapshotMaterial())
                  .allSame())
          .isTrue();
    }
    verify(repository, times(2)).save(any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void codecAndAesEncryptedStorePreserveExactMaterialBeforeSessionReconstruction(
      boolean duplicate) {
    URI base = URI.create("https://secret-domain.invalid/SECRET_TENANT_PATH/");
    var material =
        new VulcanSessionMaterial(
            base,
            base.resolve("SUPER_SECRET_REFERER"),
            "SUPER_SECRET_TOKEN",
            "SUPER_SECRET_APPGUID",
            "SECRET_NAME=SUPER_SECRET_COOKIE_A;  "
                + (duplicate ? "SECRET_NAME" : "SECOND_NAME")
                + "=SUPER_SECRET_COOKIE_B");
    var codec = new SecretPayloadCodec();
    byte[] encoded = codec.encodeSession(material);
    var decoded = codec.decodeSession(encoded);
    assertExact(material, decoded);
    assertThat(Arrays.equals(encoded, codec.encodeSession(decoded))).isTrue();
    var repository = mock(VulcanAccountSecretRepository.class);
    var row = new AtomicReference<VulcanAccountSecretEntity>();
    when(repository.findById(1L)).thenAnswer(i -> Optional.ofNullable(row.get()));
    when(repository.save(any()))
        .thenAnswer(
            i -> {
              row.set(i.getArgument(0));
              return row.get();
            });
    var store =
        new EncryptedVulcanSecretStore(
            repository,
            new AesGcmCipher(
                VulcanMasterKey.fromBase64(Base64.getEncoder().encodeToString(new byte[32]))),
            codec);
    store.replace(1, material, null, Instant.EPOCH);
    assertExact(material, store.loadSession(1));
    // Existing-row update also decrypts and preserves the payload without reconstruction.
    store.replace(1, material, null, Instant.EPOCH.plusSeconds(1));
    assertExact(material, store.loadSession(1));
    verify(repository, times(2)).save(any());
  }

  private static void assertExact(VulcanSessionMaterial expected, VulcanSessionMaterial actual) {
    // Boolean assertions deliberately avoid rendering secret-bearing payloads on failure.
    assertThat(SessionMaterialTestSupport.compare(expected, actual).allSame()).isTrue();
    assertThat(cookiePairs(expected).equals(cookiePairs(actual))).isTrue();
  }
}
