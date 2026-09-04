package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.RememberedCredentials;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class SecretPayloadCodec {

  private static final int SESSION_MAGIC = 0x56534D31;
  private static final int CREDENTIAL_MAGIC = 0x56435231;
  private static final int MAX_FIELD_BYTES = 1024 * 1024;

  public byte[] encodeSession(VulcanSessionMaterial material) {
    return write(
        SESSION_MAGIC,
        material.applicationBaseUri().toASCIIString(),
        material.refererUri().toASCIIString(),
        material.requestVerificationToken(),
        material.appGuid(),
        material.cookieHeader());
  }

  public VulcanSessionMaterial decodeSession(byte[] payload) {
    String[] fields = read(payload, SESSION_MAGIC, 5);
    try {
      return new VulcanSessionMaterial(
          URI.create(fields[0]), URI.create(fields[1]), fields[2], fields[3], fields[4]);
    } catch (RuntimeException exception) {
      throw new SecretDecryptionException();
    }
  }

  public byte[] encodeCredentials(RememberedCredentials credentials) {
    char[] password = credentials.password();
    try {
      return write(
          CREDENTIAL_MAGIC,
          credentials.portalUri().toASCIIString(),
          credentials.login(),
          new String(password));
    } finally {
      java.util.Arrays.fill(password, '\0');
    }
  }

  public RememberedCredentials decodeCredentials(byte[] payload) {
    String[] fields = read(payload, CREDENTIAL_MAGIC, 3);
    try {
      return new RememberedCredentials(URI.create(fields[0]), fields[1], fields[2].toCharArray());
    } catch (RuntimeException exception) {
      throw new SecretDecryptionException();
    }
  }

  private static byte[] write(int magic, String... fields) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        output.writeInt(magic);
        for (String field : fields) {
          byte[] value = field.getBytes(StandardCharsets.UTF_8);
          output.writeInt(value.length);
          output.write(value);
        }
      }
      return bytes.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("Secret serialization failed", exception);
    }
  }

  private static String[] read(byte[] payload, int expectedMagic, int fieldCount) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
      if (input.readInt() != expectedMagic) {
        throw new SecretDecryptionException();
      }
      String[] fields = new String[fieldCount];
      for (int index = 0; index < fieldCount; index++) {
        int length = input.readInt();
        if (length < 0 || length > MAX_FIELD_BYTES) {
          throw new SecretDecryptionException();
        }
        byte[] value = input.readNBytes(length);
        if (value.length != length) {
          throw new SecretDecryptionException();
        }
        fields[index] = new String(value, StandardCharsets.UTF_8);
      }
      if (input.available() != 0) {
        throw new SecretDecryptionException();
      }
      return fields;
    } catch (IOException | RuntimeException exception) {
      if (exception instanceof SecretDecryptionException secretFailure) {
        throw secretFailure;
      }
      throw new SecretDecryptionException();
    }
  }
}
