package io.github.bohdankordon.vulcanschedulemonitor.telegram.interactive;

public record ClassSelectionCallback(Action action, Long catalogClassId, int page) {

  public enum Action {
    TOGGLE,
    PAGE
  }

  public ClassSelectionCallback {
    if (page < 0) {
      throw new IllegalArgumentException("Callback page must not be negative");
    }
    if ((action == Action.TOGGLE) != (catalogClassId != null)) {
      throw new IllegalArgumentException("Only toggle callbacks carry a catalog class id");
    }
    if (catalogClassId != null && catalogClassId <= 0) {
      throw new IllegalArgumentException("Catalog class id must be positive");
    }
  }
}
