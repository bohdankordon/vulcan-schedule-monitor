package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog;

import java.time.LocalDate;

public record CatalogClass(
    long id,
    long journalId,
    long classId,
    String name,
    String schoolUnit,
    Integer grade,
    int schoolYear,
    LocalDate validFrom,
    LocalDate validTo) {}
