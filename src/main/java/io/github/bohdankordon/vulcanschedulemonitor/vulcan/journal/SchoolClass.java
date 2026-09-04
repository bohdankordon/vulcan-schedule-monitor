package io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal;

import java.time.LocalDate;
import java.util.Objects;

public record SchoolClass(
    long journalId,
    long classId,
    String name,
    String schoolUnit,
    int grade,
    int schoolYear,
    LocalDate validFrom,
    LocalDate validTo) {

  public SchoolClass {
    if (journalId <= 0 || classId <= 0) {
      throw new IllegalArgumentException("Journal and class IDs must be positive");
    }
    name = requireText(name, "name");
    schoolUnit = requireText(schoolUnit, "schoolUnit");
    Objects.requireNonNull(validFrom, "validFrom must not be null");
    Objects.requireNonNull(validTo, "validTo must not be null");
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return value.trim();
  }
}
