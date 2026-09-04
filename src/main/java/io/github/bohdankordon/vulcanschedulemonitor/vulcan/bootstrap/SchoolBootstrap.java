package io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SchoolBootstrap {

  private final int currentSchoolYear;
  private final List<LessonPeriod> lessonPeriods;
  private final Map<Long, LessonPeriod> lessonPeriodsByExternalId;

  public SchoolBootstrap(int currentSchoolYear, List<LessonPeriod> lessonPeriods) {
    this.currentSchoolYear = currentSchoolYear;
    this.lessonPeriods = List.copyOf(lessonPeriods);
    Map<Long, LessonPeriod> indexed = new LinkedHashMap<>();
    for (LessonPeriod lessonPeriod : this.lessonPeriods) {
      if (indexed.putIfAbsent(lessonPeriod.externalId(), lessonPeriod) != null) {
        throw new IllegalArgumentException("Lesson period external IDs must be unique");
      }
    }
    this.lessonPeriodsByExternalId = Map.copyOf(indexed);
  }

  public int currentSchoolYear() {
    return currentSchoolYear;
  }

  public List<LessonPeriod> lessonPeriods() {
    return lessonPeriods;
  }

  public Map<Long, LessonPeriod> lessonPeriodsByExternalId() {
    return lessonPeriodsByExternalId;
  }

  public Optional<LessonPeriod> lessonPeriod(long externalId) {
    return Optional.ofNullable(lessonPeriodsByExternalId.get(externalId));
  }
}
