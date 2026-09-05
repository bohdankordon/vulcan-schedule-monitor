package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** NSJ1: nine fixed-order, length-prefixed UTF-8 fields. Never serialize this object. */
final class NativeSessionBaselineInput implements AutoCloseable {
  static final String ENDPOINT = "PlanLekcji.mvc/GetPlanLekcjiContext";
  private final String[] fields;

  private NativeSessionBaselineInput(String[] fields) {
    this.fields = fields;
  }

  static NativeSessionBaselineInput read(InputStream stream) {
    byte[] bytes = null;
    String[] values = new String[9];
    try {
      bytes = stream.readNBytes(65537);
      if (bytes.length > 65536) throw invalid();
      ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      if (buffer.remaining() < 4
          || buffer.get() != 'N'
          || buffer.get() != 'S'
          || buffer.get() != 'J'
          || buffer.get() != '1') throw invalid();
      for (int index = 0; index < values.length; index++) {
        if (buffer.remaining() < 4) throw invalid();
        int size = buffer.getInt();
        if (size < 1 || size > 8192 || size > buffer.remaining()) throw invalid();
        values[index] =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(buffer.slice().limit(size))
                .toString();
        buffer.position(buffer.position() + size);
      }
      if (buffer.hasRemaining()) throw invalid();
      return new NativeSessionBaselineInput(values);
    } catch (Exception ignored) {
      Arrays.fill(values, null);
      throw invalid();
    } finally {
      if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
  }

  record Material(
      VulcanSession session,
      long journal,
      LocalDate dataDate,
      Map<String, String> capturedForm,
      int cookieCount,
      String refererContext) {
    @Override
    public String toString() {
      return "NativeSessionMaterial[redacted]";
    }
  }

  Material validate() {
    return validate(null);
  }

  /** The CLI never exposes this loopback-only test seam. No external test URI is accepted. */
  Material validate(URI testEndpoint) {
    try {
      URI target = URI.create(fields[0]);
      URI referer = URI.create(fields[1]);
      if (testEndpoint == null) {
        if (!new PortalUrlValidator().isAllowedRuntimeUri(target)) throw invalid();
      } else if (!target.equals(testEndpoint)
          || !"http".equals(target.getScheme())
          || !Set.of("127.0.0.1", "[::1]", "::1").contains(target.getHost())) throw invalid();
      if (target.getUserInfo() != null
          || target.getRawQuery() != null
          || target.getRawFragment() != null
          || !target.getRawPath().endsWith("/" + ENDPOINT)
          || !safePath(target.getRawPath())
          || !target.normalize().equals(target)) throw invalid();
      String prefix =
          target.getRawPath().substring(0, target.getRawPath().length() - ENDPOINT.length());
      URI base =
          new URI(target.getScheme(), null, target.getHost(), target.getPort(), prefix, null, null);
      if (!Objects.equals(target.getScheme(), referer.getScheme())
          || !Objects.equals(target.getHost(), referer.getHost())
          || port(target) != port(referer)
          || referer.getUserInfo() != null
          || referer.getRawFragment() != null
          || !safePath(referer.getRawPath())
          || !referer.normalize().equals(referer)
          || !referer.getRawPath().startsWith(prefix)) throw invalid();
      for (int index : new int[] {2, 3, 4}) {
        if (fields[index].isBlank() || !fields[index].matches("[\\x20-\\x7e]+")) throw invalid();
      }
      int count = cookies(fields[4]);
      if (!fields[8].matches("[1-9][0-9]{0,18}")) throw invalid();
      long journal = Long.parseLong(fields[8]);
      LocalDateTime from = timestamp(fields[5]),
          to = timestamp(fields[6]),
          data = timestamp(fields[7]);
      if (from.getDayOfWeek() != DayOfWeek.MONDAY
          || to.getDayOfWeek() != DayOfWeek.SUNDAY
          || !from.toLocalDate().plusDays(6).equals(to.toLocalDate())
          || data.toLocalDate().isBefore(from.toLocalDate())
          || data.toLocalDate().isAfter(to.toLocalDate())) throw invalid();
      var session =
          VulcanSession.fromBrowserSession(base, fields[2], fields[3], fields[4], referer);
      if (!session.resolve(ENDPOINT).equals(target)) throw invalid();
      return new Material(
          session,
          journal,
          data.toLocalDate(),
          Map.of(
              "dataOd", fields[5], "dataDo", fields[6], "data", fields[7], "idDziennik", fields[8]),
          count,
          refererContext(referer.getPath().substring(prefix.length())));
    } catch (Exception ignored) {
      throw invalid();
    }
  }

  private static boolean safePath(String path) {
    return path != null
        && !path.contains("%")
        && !path.contains("\\")
        && !path.contains("//")
        && Arrays.stream(path.split("/")).noneMatch(part -> part.equals(".") || part.equals(".."));
  }

  private static int port(URI uri) {
    return uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
  }

  static int cookies(String header) {
    Set<String> names = new HashSet<>();
    for (String pair : header.split(";", -1)) {
      String[] parts = pair.trim().split("=", 2);
      if (parts.length != 2
          || !parts[0].matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
          || !parts[1].matches("[\\x21\\x23-\\x2b\\x2d-\\x3a\\x3c-\\x5b\\x5d-\\x7e]*")
          || !names.add(parts[0])) throw invalid();
    }
    if (names.isEmpty() || names.size() > 1000) throw invalid();
    return names.size();
  }

  private static LocalDateTime timestamp(String value) {
    if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")) throw invalid();
    return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  static String refererContext(String relative) {
    String lower = relative.toLowerCase(Locale.ROOT);
    if (lower.matches("planlekcji\\.mvc(?:/.*)?") && !lower.endsWith("/getplanlekcjicontext"))
      return "PLAN_PAGE";
    if (lower.matches("(?:dziennik|dzienniki|journal)\\.mvc(?:/.*)?")) return "JOURNAL_PAGE";
    if (lower.isEmpty() || lower.matches("(?:home|start|default|index)\\.mvc(?:/index)?/?"))
      return "HOME_OR_LANDING";
    return "OTHER_ALLOWED";
  }

  static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_INPUT");
  }

  @Override
  public void close() {
    Arrays.fill(fields, null);
  }

  @Override
  public String toString() {
    return "NativeSessionBaselineInput[redacted]";
  }
}
