package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Synthetic measurement reduction. Only fixed keys, enums, booleans and counts escape. */
final class Schedule429Fingerprint {
  private static final Map<String, String> HEADERS =
      Map.ofEntries(
          Map.entry("userAgent", "User-Agent"), Map.entry("accept", "Accept"),
          Map.entry("acceptLanguage", "Accept-Language"),
              Map.entry("secFetchSite", "Sec-Fetch-Site"),
          Map.entry("secFetchMode", "Sec-Fetch-Mode"), Map.entry("secFetchDest", "Sec-Fetch-Dest"),
          Map.entry("secChUa", "Sec-CH-UA"), Map.entry("secChUaMobile", "Sec-CH-UA-Mobile"),
          Map.entry("secChUaPlatform", "Sec-CH-UA-Platform"),
              Map.entry("acceptEncoding", "Accept-Encoding"),
          Map.entry("priority", "Priority"), Map.entry("cacheControl", "Cache-Control"),
          Map.entry("pragma", "Pragma"), Map.entry("dnt", "DNT"));
  private static final String QUALITY = "(?:0(?:\\.\\d{1,3})?|1(?:\\.0{1,3})?)";
  private static final Pattern LANGUAGE =
      Pattern.compile(
          "^(en|pl|uk|ru)(?:-[A-Za-z0-9]{1,8})*(?:\\s*;\\s*q=" + QUALITY + ")?$",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern ENCODING =
      Pattern.compile(
          "^(gzip|br|deflate|zstd)(?:\\s*;\\s*q=" + QUALITY + ")?$", Pattern.CASE_INSENSITIVE);
  private static final List<String> ENCODINGS = List.of("gzip", "br", "deflate", "zstd", "other");

  private Schedule429Fingerprint() {}

  static Map<String, Object> headers(Map<String, String> raw) {
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    headers.putAll(raw);
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("requestHeaderCount", headers.size());
    HEADERS.forEach(
        (key, name) -> facts.put("headers." + key + ".present", headers.containsKey(name)));
    facts.put("headers.userAgent.family", userAgent(headers.get("User-Agent")));
    facts.put("headers.secChUaMobile.category", mobile(headers.get("Sec-CH-UA-Mobile")));
    facts.put("headers.secChUaPlatform.category", platform(headers.get("Sec-CH-UA-Platform")));
    facts.put("headers.accept.profile", accept(headers.get("Accept")));
    String language = headers.get("Accept-Language");
    var languages =
        Arrays.stream(Objects.requireNonNullElse(language, "").split(",", -1))
            .filter(value -> !value.isBlank())
            .toList();
    String family = "OTHER";
    if (!languages.isEmpty()) {
      var matcher = LANGUAGE.matcher(languages.getFirst().trim());
      if (matcher.matches()) family = matcher.group(1).toUpperCase(Locale.ROOT);
    }
    facts.put("headers.acceptLanguage.family", language == null ? "ABSENT" : family);
    facts.put("headers.acceptLanguage.multipleLanguages", languages.size() > 1);
    String dest = headers.get("Sec-Fetch-Dest");
    facts.put(
        "headers.secFetchDest.category",
        dest == null
            ? "absent"
            : Set.of("empty", "document", "iframe", "script", "style", "image").contains(dest)
                ? dest
                : "other");
    ENCODINGS.forEach(encoding -> facts.put("headers.acceptEncoding." + encoding, false));
    if (headers.containsKey("Accept-Encoding")) {
      for (String part : headers.get("Accept-Encoding").split(",", -1)) {
        var matcher = ENCODING.matcher(part.trim());
        String coding = matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT) : "other";
        facts.put("headers.acceptEncoding." + coding, true);
      }
    }
    validate(facts, headerSchema());
    return facts;
  }

  static String userAgent(String value) {
    if (value == null) return "ABSENT";
    if (value.matches("^Java-http-client/[0-9][0-9A-Za-z.+_-]*$")) return "JAVA_HTTP_CLIENT";
    if (Pattern.compile("(?:^| )(?:Chrome|Chromium|CriOS)/[0-9]").matcher(value).find())
      return "CHROMIUM_BROWSER";
    if (Pattern.compile("(?:^| )(?:Firefox|FxiOS)/[0-9]").matcher(value).find())
      return "FIREFOX_BROWSER";
    if (Pattern.compile("(?:^| )Version/[0-9]").matcher(value).find()
        && Pattern.compile("(?:^| )Safari/[0-9]").matcher(value).find()) return "SAFARI_BROWSER";
    return "OTHER";
  }

  static String mobile(String value) {
    if (value == null) return "ABSENT";
    return switch (value) {
      case "?1" -> "MOBILE";
      case "?0" -> "NOT_MOBILE";
      default -> "OTHER";
    };
  }

  static String platform(String value) {
    if (value == null) return "ABSENT";
    return switch (value) {
      case "\"Windows\"" -> "WINDOWS";
      case "\"macOS\"" -> "MACOS";
      case "\"Linux\"" -> "LINUX";
      case "\"Android\"" -> "ANDROID";
      case "\"iOS\"" -> "IOS";
      default -> "OTHER";
    };
  }

  static String accept(String value) {
    if (value == null) return "ABSENT";
    if (value.contains("\r") || value.contains("\n")) return "OTHER";
    return switch (value.trim().replaceAll("\\s*,\\s*", ",")) {
      case "*/*" -> "STAR_STAR_ONLY";
      case "application/json" -> "JSON_EXPLICIT";
      case "application/json,text/javascript,*/*; q=0.01",
          "application/json,text/javascript,*/*;q=0.01" ->
          "JQUERY_JSON";
      case "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7" ->
          "HTML_NAVIGATION";
      default -> "OTHER";
    };
  }

  static String protocol(String value) {
    if (value == null || value.isBlank()) return "UNKNOWN";
    return switch (value.toUpperCase(Locale.ROOT)) {
      case "H2", "HTTP/2", "HTTP/2.0" -> "HTTP_2";
      case "HTTP/1.1" -> "HTTP_1_1";
      default -> "OTHER";
    };
  }

  private static Predicate<Object> choices(String... values) {
    Set<String> allowed = Set.of(values);
    return value -> value instanceof String && allowed.contains(value);
  }

  private static Map<String, Predicate<Object>> headerSchema() {
    Map<String, Predicate<Object>> schema = new HashMap<>();
    schema.put(
        "requestHeaderCount",
        value -> value instanceof Integer count && count >= 0 && count <= 1_000_000);
    HEADERS
        .keySet()
        .forEach(
            key -> schema.put("headers." + key + ".present", value -> value instanceof Boolean));
    schema.put(
        "headers.userAgent.family",
        choices(
            "CHROMIUM_BROWSER",
            "FIREFOX_BROWSER",
            "SAFARI_BROWSER",
            "JAVA_HTTP_CLIENT",
            "OTHER",
            "ABSENT"));
    schema.put(
        "headers.secChUaMobile.category", choices("MOBILE", "NOT_MOBILE", "OTHER", "ABSENT"));
    schema.put(
        "headers.secChUaPlatform.category",
        choices("WINDOWS", "MACOS", "LINUX", "ANDROID", "IOS", "OTHER", "ABSENT"));
    schema.put(
        "headers.accept.profile",
        choices(
            "STAR_STAR_ONLY",
            "JSON_EXPLICIT",
            "JQUERY_JSON",
            "HTML_NAVIGATION",
            "OTHER",
            "ABSENT"));
    schema.put("headers.acceptLanguage.family", choices("EN", "PL", "UK", "RU", "OTHER", "ABSENT"));
    schema.put("headers.acceptLanguage.multipleLanguages", value -> value instanceof Boolean);
    schema.put(
        "headers.secFetchDest.category",
        choices("empty", "document", "iframe", "script", "style", "image", "other", "absent"));
    ENCODINGS.forEach(
        encoding ->
            schema.put("headers.acceptEncoding." + encoding, value -> value instanceof Boolean));
    return schema;
  }

  static void validateProjection(Map<String, Object> report) {
    var schema = headerSchema();
    schema.put("schemaVersion", value -> Integer.valueOf(2).equals(value));
    schema.put("profileSource", choices("JAVA_LOOPBACK"));
    schema.put("actualObservedHttpVersion", choices("HTTP_2", "HTTP_1_1", "OTHER", "UNKNOWN"));
    schema.put("clientPreferredVersion", choices("HTTP_2", "HTTP_1_1", "UNKNOWN"));
    for (String key :
        List.of("exactExpectedFieldSet", "weekIsMondayToSunday", "dataWithinWeek", "urlEncoded")) {
      schema.put("form." + key, value -> value instanceof Boolean);
    }
    for (String key : List.of("dataOd", "dataDo", "data")) {
      schema.put("form." + key + "Shape", choices("ISO_T_DATETIME", "OTHER"));
    }
    validate(report, schema);
  }

  private static void validate(Map<String, Object> report, Map<String, Predicate<Object>> schema) {
    if (!report.keySet().equals(schema.keySet())
        || report.entrySet().stream()
            .anyMatch(entry -> !schema.get(entry.getKey()).test(entry.getValue()))) {
      throw new IllegalArgumentException("UNSAFE_OUTPUT_GUARD");
    }
  }
}
