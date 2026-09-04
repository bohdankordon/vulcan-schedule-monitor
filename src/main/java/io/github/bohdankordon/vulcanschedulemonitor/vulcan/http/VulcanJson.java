package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import tools.jackson.databind.JsonNode;

/** Strict, sanitized access to the small subset of VULCAN JSON used by this project. */
public final class VulcanJson {

  private VulcanJson() {}

  public static JsonNode envelopeData(JsonNode envelope, String operation) {
    if (envelope == null || !envelope.isObject()) {
      throw invalid(operation);
    }
    JsonNode success = envelope.get("success");
    JsonNode data = envelope.get("data");
    if (success == null || !success.isBoolean() || !success.booleanValue() || data == null) {
      throw invalid(operation);
    }
    return data;
  }

  public static JsonNode requiredObject(JsonNode parent, String field, String operation) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || !value.isObject()) {
      throw invalid(operation);
    }
    return value;
  }

  public static JsonNode requiredArray(JsonNode parent, String field, String operation) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || !value.isArray()) {
      throw invalid(operation);
    }
    return value;
  }

  public static String requiredText(JsonNode parent, String field, String operation) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || !value.isString() || value.stringValue().isBlank()) {
      throw invalid(operation);
    }
    return value.stringValue().trim();
  }

  public static String nullableText(JsonNode parent, String field, String operation) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isString()) {
      throw invalid(operation);
    }
    String text = value.stringValue().trim();
    return text.isEmpty() ? null : text;
  }

  public static long requiredLong(JsonNode parent, String field, String operation) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || !value.isIntegralNumber()) {
      throw invalid(operation);
    }
    return value.longValue();
  }

  public static Long nullableLong(JsonNode parent, String field, String operation) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isIntegralNumber()) {
      throw invalid(operation);
    }
    return value.longValue();
  }

  public static int requiredInt(JsonNode parent, String field, String operation) {
    long value = requiredLong(parent, field, operation);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw invalid(operation);
    }
    return (int) value;
  }

  public static boolean requiredBoolean(JsonNode parent, String field, String operation) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || !value.isBoolean()) {
      throw invalid(operation);
    }
    return value.booleanValue();
  }

  public static boolean booleanOrFalse(JsonNode parent, String field, String operation) {
    JsonNode value = parent == null ? null : parent.get(field);
    if (value == null || value.isNull()) {
      return false;
    }
    if (!value.isBoolean()) {
      throw invalid(operation);
    }
    return value.booleanValue();
  }

  private static VulcanProtocolException invalid(String operation) {
    return new VulcanProtocolException(operation);
  }
}
