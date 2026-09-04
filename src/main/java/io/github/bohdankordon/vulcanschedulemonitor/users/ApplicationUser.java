package io.github.bohdankordon.vulcanschedulemonitor.users;

import java.time.Instant;
import java.util.Objects;

public record ApplicationUser(long id, boolean active, Instant createdAt, Instant updatedAt) {

  public ApplicationUser {
    if (id <= 0) {
      throw new IllegalArgumentException("Application user id must be positive");
    }
    Objects.requireNonNull(createdAt, "createdAt must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
  }
}
