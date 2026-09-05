package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Reduces ephemeral provider inputs to booleans/counts/categories; never returns raw values. */
public final class Schedule429Structure {
  public static final Set<String> FIELDS = Set.of("dataOd", "dataDo", "idDziennik", "data");
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private Schedule429Structure() {}

  public static String referer(String value) {
    try {
      URI uri = URI.create(value);
      if (!new PortalUrlValidator().isAllowedRuntimeUri(uri)) return "UNAVAILABLE";
      // Inspect only generic route segments, never query values or tenant names.
      String path = uri.getPath().toLowerCase(Locale.ROOT);
      String fragment = Objects.requireNonNullElse(uri.getFragment(), "").toLowerCase(Locale.ROOT);
      if (path.matches(".*/planlekcji(?:\\.mvc)?(?:/.*)?")
          || fragment.matches("(?:.*/)?planlekcji(?:/.*)?")) return "PLAN_PAGE";
      if (path.matches(".*/dziennik(?:\\.mvc)?(?:/.*)?")) return "JOURNAL_PAGE";
      if (path.endsWith("/") || path.matches(".*/(?:home|start|index)(?:\\.mvc)?(?:/.*)?"))
        return "HOME_OR_LANDING";
      return "OTHER_ALLOWED";
    } catch (RuntimeException ignored) {
      return "UNAVAILABLE";
    }
  }

  public static void headers(Schedule429Report report, String side, Map<String, String> raw) {
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.putAll(raw);
    Map<String, String> mapping =
        Map.ofEntries(
            Map.entry("xRequestedWithPresent", "x-requested-with"),
                Map.entry("originPresent", "origin"),
            Map.entry("refererPresent", "referer"),
                Map.entry("verificationHeaderPresent", "x-v-requestverificationtoken"),
            Map.entry("appGuidHeaderPresent", "x-v-appguid"),
                Map.entry("contentTypePresent", "content-type"),
            Map.entry("userAgentPresent", "user-agent"), Map.entry("acceptPresent", "accept"),
            Map.entry("acceptLanguagePresent", "accept-language"),
                Map.entry("cookieHeaderPresent", "cookie"));
    mapping.forEach(
        (key, header) ->
            report.put(
                side + "." + key, headers.containsKey(header) && !headers.get(header).isBlank()));
    report.put(
        side + ".fetchMetadataPresent",
        headers.keySet().stream()
            .anyMatch(key -> key.toLowerCase(Locale.ROOT).startsWith("sec-fetch-")));
    report.put(side + ".cookieCount", cookiePairs(headers.get("cookie")).size());
    report.put(side + ".refererContext", referer(headers.get("referer")));
  }

  private static Set<String> cookiePairs(String header) {
    if (header == null || header.isBlank()) return Set.of();
    Set<String> result = new HashSet<>();
    for (String pair : header.split(";")) if (pair.contains("=")) result.add(pair.trim());
    return result;
  }

  public static void verificationDrift(
      Schedule429Report report, VulcanSessionMaterial postLogin, VulcanSessionMaterial verified) {
    int before = cookiePairs(postLogin.cookieHeader()).size();
    int after = cookiePairs(verified.cookieHeader()).size();
    String beforeContext = referer(postLogin.refererUri().toASCIIString());
    String afterContext = referer(verified.refererUri().toASCIIString());
    report.put("postLoginCookieCount", before);
    report.put("verifiedCookieCount", after);
    report.put("verificationChangedCookieCount", before != after);
    report.put(
        "verificationChangedCookieMaterial",
        !cookiePairs(postLogin.cookieHeader()).equals(cookiePairs(verified.cookieHeader())));
    report.put("postLoginRefererContext", beforeContext);
    report.put("verifiedRefererContext", afterContext);
    report.put("verificationChangedRefererContext", !beforeContext.equals(afterContext));
  }

  public static void initialCookies(Schedule429Report report, VulcanSessionMaterial material) {
    report.put("postLoginCookieCount", cookiePairs(material.cookieHeader()).size());
  }

  public static Map<String, String> formValues(String body) {
    Map<String, String> values = new HashMap<>();
    if (body == null || body.length() > 16384) return Map.of();
    try {
      for (String pair : body.split("&")) {
        String[] parts = pair.split("=", 2);
        if (parts.length != 2) return Map.of();
        String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
        if (values.putIfAbsent(key, URLDecoder.decode(parts[1], StandardCharsets.UTF_8)) != null)
          return Map.of();
      }
      return values;
    } catch (RuntimeException ignored) {
      return Map.of();
    }
  }

  public record FormFacts(
      boolean fields,
      boolean fromIso,
      boolean toIso,
      boolean anchorIso,
      boolean week,
      boolean anchorAtStart,
      boolean encoded) {}

  public static FormFacts form(String method, String body, String contentType, LocalDate week) {
    Map<String, String> values = formValues(body);
    boolean fields = values.keySet().equals(FIELDS);
    boolean from = iso(values.get("dataOd")),
        to = iso(values.get("dataDo")),
        anchor = iso(values.get("data"));
    return new FormFacts(
        fields,
        from,
        to,
        anchor,
        from
            && to
            && values.get("dataOd").equals(stamp(week))
            && values.get("dataDo").equals(stamp(week.plusDays(6))),
        anchor && values.get("data").equals(stamp(week)),
        "POST".equals(method)
            && contentType != null
            && contentType
                .toLowerCase(Locale.ROOT)
                .startsWith("application/x-www-form-urlencoded"));
  }

  public static void formReport(Schedule429Report report, String side, FormFacts facts) {
    report.put(side + ".formFieldSetMatchesExpected", facts.fields());
    report.put(side + ".dataOdIsoTimestamp", facts.fromIso());
    report.put(side + ".dataDoIsoTimestamp", facts.toIso());
    report.put(side + ".dataIsoTimestamp", facts.anchorIso());
    report.put(side + ".weekBoundarySemantics", facts.week());
    report.put(side + ".dataAtWeekStart", facts.anchorAtStart());
    report.put(side + ".formUrlEncoded", facts.encoded());
  }

  private static boolean iso(String value) {
    if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}")) return false;
    try {
      LocalDateTime.parse(value);
      return true;
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  public static String stamp(LocalDate date) {
    return date.atStartOfDay().format(TIMESTAMP);
  }

  public static String statusFamily(int status) {
    return status >= 200 && status < 600 ? (status / 100) + "xx" : "OTHER";
  }

  public static String contentFamily(String type) {
    if (type == null) return "other";
    String t = type.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
    return t.equals("application/json") || t.endsWith("+json")
        ? "json"
        : t.equals("text/html") ? "html" : "other";
  }

  public static boolean jsonEnvelope(Schedule429Report report, byte[] bytes) {
    try {
      var root = new ObjectMapper().readTree(bytes);
      report.put("browser.jsonParseable", root != null);
      boolean envelope =
          root != null
              && root.isObject()
              && root.path("success").isBoolean()
              && root.path("success").booleanValue()
              && root.has("data");
      report.put("browser.envelopePresent", envelope);
      boolean arrays =
          envelope
              && root.path("data").path("planLekcji").isArray()
              && root.path("data").path("planLekcjiZeZmianami").isArray();
      report.put("browser.scheduleArraysPresent", arrays);
      return envelope && arrays;
    } catch (RuntimeException ignored) {
      report.put("browser.jsonParseable", false);
      return false;
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }
}
