package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public final class ClassSelectionCallbackParser {

  private static final int MAX_BYTES = 64;
  private static final int MAX_PAGE = 100_000;

  public Optional<ClassSelectionCallback> parse(String data) {
    if (data == null
        || data.isBlank()
        || data.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      return Optional.empty();
    }
    String[] parts = data.split(":", -1);
    if (parts.length < 3 || !"c1".equals(parts[0])) {
      return Optional.empty();
    }
    try {
      if (parts.length == 4 && "t".equals(parts[1])) {
        long catalogClassId = Long.parseLong(parts[2]);
        int page = parsePage(parts[3]);
        if (catalogClassId <= 0) {
          return Optional.empty();
        }
        return Optional.of(
            new ClassSelectionCallback(ClassSelectionCallback.Action.TOGGLE, catalogClassId, page));
      }
      if (parts.length == 3 && "p".equals(parts[1])) {
        return Optional.of(
            new ClassSelectionCallback(
                ClassSelectionCallback.Action.PAGE, null, parsePage(parts[2])));
      }
    } catch (NumberFormatException ignored) {
      return Optional.empty();
    }
    return Optional.empty();
  }

  private static int parsePage(String value) {
    int page = Integer.parseInt(value);
    if (page < 0 || page > MAX_PAGE) {
      throw new NumberFormatException("Page out of range");
    }
    return page;
  }
}
