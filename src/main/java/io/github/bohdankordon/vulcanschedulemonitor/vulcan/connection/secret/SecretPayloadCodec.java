package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.RememberedCredentials;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanCookieMaterial;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class SecretPayloadCodec {

  private static final int SESSION_MAGIC = 0x56534D31;
  private static final int SESSION_V2_MAGIC = 0x56534D32;
  private static final int MAX_SESSION_BYTES = 32 * 1024 * 1024;
  private static final int CREDENTIAL_MAGIC = 0x56435231;
  private static final int MAX_FIELD_BYTES = 1024 * 1024;

  public byte[] encodeSession(VulcanSessionMaterial material) {
    // Only compatibility callers can supply legacy material. All live snapshots/captures write V2.
    if (material.cookieRepresentation()
        == VulcanSessionMaterial.CookieRepresentation.LEGACY_HEADER) {
      return write(
          SESSION_MAGIC,
          material.applicationBaseUri().toASCIIString(),
          material.refererUri().toASCIIString(),
          material.requestVerificationToken(),
          material.appGuid(),
          material.legacyCookieHeader());
    }
    try {
      var bytes = new ByteArrayOutputStream();
      try (var output = new DataOutputStream(bytes)) {
        output.writeInt(SESSION_V2_MAGIC);
        writeField(output, material.applicationBaseUri().toASCIIString(), MAX_FIELD_BYTES);
        writeField(output, material.refererUri().toASCIIString(), MAX_FIELD_BYTES);
        writeField(output, material.requestVerificationToken(), MAX_FIELD_BYTES);
        writeField(output, material.appGuid(), MAX_FIELD_BYTES);
        output.writeInt(material.cookies().size());
        for (var cookie : material.cookies()) {
          writeField(output, cookie.name(), VulcanCookieMaterial.MAX_NAME_BYTES);
          writeField(output, cookie.value(), VulcanCookieMaterial.MAX_VALUE_BYTES);
          writeField(output, cookie.path(), VulcanCookieMaterial.MAX_PATH_BYTES);
          writeField(output, cookie.domain(), VulcanCookieMaterial.MAX_DOMAIN_BYTES);
          output.writeByte(cookie.secure() ? 1 : 0);
          output.writeByte(cookie.httpOnly() ? 1 : 0);
        }
      }
      return bytes.toByteArray();
    } catch (IOException | RuntimeException ignored) {
      throw new IllegalArgumentException("Secret serialization failed");
    }
  }

  public VulcanSessionMaterial decodeSession(byte[] payload) {
    try {
      if (payload == null || payload.length > MAX_SESSION_BYTES)
        throw new SecretDecryptionException();
      try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
        int magic = input.readInt();
        if (magic == SESSION_MAGIC) {
          String[] fields = read(payload, SESSION_MAGIC, 5);
          return new VulcanSessionMaterial(
              URI.create(fields[0]), URI.create(fields[1]), fields[2], fields[3], fields[4]);
        }
        if (magic != SESSION_V2_MAGIC) throw new SecretDecryptionException();
        URI base = URI.create(readField(input, MAX_FIELD_BYTES, false));
        URI referer = URI.create(readField(input, MAX_FIELD_BYTES, false));
        String token = readField(input, MAX_FIELD_BYTES, false);
        String guid = readField(input, MAX_FIELD_BYTES, false);
        int count = input.readInt();
        if (count < 1 || count > VulcanCookieMaterial.MAX_COOKIES)
          throw new SecretDecryptionException();
        var cookies = new ArrayList<VulcanCookieMaterial>(count);
        for (int i = 0; i < count; i++) {
          cookies.add(
              new VulcanCookieMaterial(
                  readField(input, VulcanCookieMaterial.MAX_NAME_BYTES, false),
                  readField(input, VulcanCookieMaterial.MAX_VALUE_BYTES, false),
                  readField(input, VulcanCookieMaterial.MAX_PATH_BYTES, true),
                  readField(input, VulcanCookieMaterial.MAX_DOMAIN_BYTES, true),
                  readBoolean(input),
                  readBoolean(input)));
        }
        if (input.available() != 0) throw new SecretDecryptionException();
        return VulcanSessionMaterial.structured(base, referer, token, guid, cookies);
      }
    } catch (IOException | RuntimeException ignored) {
      throw new SecretDecryptionException();
    }
  }

  private static boolean readBoolean(DataInputStream input) throws IOException {
    int value = input.readUnsignedByte();
    if (value > 1) throw new SecretDecryptionException();
    return value == 1;
  }

  private static void writeField(DataOutputStream output, String field, int max)
      throws IOException {
    if (field == null) {
      output.writeInt(-1);
      return;
    }
    if (field.length() > max) throw new IllegalArgumentException("Secret field is invalid");
    byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > max) throw new IllegalArgumentException("Secret field is invalid");
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readField(DataInputStream input, int max, boolean nullable)
      throws IOException {
    int length = input.readInt();
    if (nullable && length == -1) return null;
    if (length < 0 || length > max) throw new SecretDecryptionException();
    byte[] bytes = input.readNBytes(length);
    if (bytes.length != length) throw new SecretDecryptionException();
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
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
