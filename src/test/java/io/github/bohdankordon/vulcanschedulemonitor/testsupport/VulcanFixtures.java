package io.github.bohdankordon.vulcanschedulemonitor.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class VulcanFixtures {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private VulcanFixtures() {}

  public static JsonNode json(String name) {
    try (InputStream stream = resource(name)) {
      return OBJECT_MAPPER.readTree(stream);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not load synthetic test fixture", exception);
    }
  }

  public static String text(String name) {
    try (InputStream stream = resource(name)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not load synthetic test fixture", exception);
    }
  }

  private static InputStream resource(String name) {
    InputStream stream =
        VulcanFixtures.class.getResourceAsStream("/fixtures/vulcan/" + name + ".json");
    if (stream == null) {
      throw new IllegalArgumentException("Unknown synthetic test fixture");
    }
    return stream;
  }
}
