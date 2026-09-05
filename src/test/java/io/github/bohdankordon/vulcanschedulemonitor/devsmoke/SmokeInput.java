package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** VSM1: magic, then three little-endian length-prefixed UTF-8 fields. Stdin only. */
final class SmokeInput implements AutoCloseable {
  private static final int MAX_PAYLOAD = 32_768;
  final String portal;
  final String login;
  final char[] password;

  private SmokeInput(String portal, String login, char[] password) {
    this.portal = portal;
    this.login = login;
    this.password = password;
  }

  static SmokeInput read(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(MAX_PAYLOAD + 1);
    char[] password = null;
    try {
      if (bytes.length > MAX_PAYLOAD) throw invalid();
      ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      if (buffer.remaining() < 4
          || buffer.get() != 'V'
          || buffer.get() != 'S'
          || buffer.get() != 'M'
          || buffer.get() != '1') throw invalid();
      String portal = textField(buffer, 8192);
      String login = textField(buffer, 4096);
      password = field(buffer, 4096);
      if (buffer.hasRemaining() || portal.isBlank() || login.isBlank() || password.length == 0)
        throw invalid();
      SmokeInput result = new SmokeInput(portal, login, password);
      password = null;
      return result;
    } finally {
      Arrays.fill(bytes, (byte) 0);
      if (password != null) Arrays.fill(password, '\0');
    }
  }

  private static char[] field(ByteBuffer buffer, int max) throws CharacterCodingException {
    if (buffer.remaining() < 4) throw invalid();
    int length = buffer.getInt();
    if (length < 1 || length > max || length > buffer.remaining()) throw invalid();
    ByteBuffer field = buffer.slice().limit(length);
    CharBuffer decoded =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(field);
    char[] result = new char[decoded.remaining()];
    decoded.get(result);
    if (decoded.hasArray()) Arrays.fill(decoded.array(), '\0');
    buffer.position(buffer.position() + length);
    return result;
  }

  private static String textField(ByteBuffer buffer, int max) throws CharacterCodingException {
    char[] characters = field(buffer, max);
    try {
      return new String(characters);
    } finally {
      Arrays.fill(characters, '\0');
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid smoke input");
  }

  @Override
  public void close() {
    Arrays.fill(password, '\0');
  }

  @Override
  public String toString() {
    return "SmokeInput[redacted]";
  }
}
