package io.github.bohdankordon.vulcanschedulemonitor.monitoring.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(
    name = "schedule_change_state",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_schedule_change_state_scope_key",
            columnNames = {"scope_id", "change_key"}),
    indexes = {
      @Index(name = "ix_schedule_change_state_scope", columnList = "scope_id"),
      @Index(
          name = "ix_schedule_change_state_lesson_slot",
          columnList = "lesson_date, lesson_period_id")
    })
class ScheduleChangeStateEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "scope_id", nullable = false)
  private TrackingScopeEntity scope;

  @Column(name = "change_key", nullable = false, length = 64)
  private String changeKey;

  @Column(nullable = false, length = 64)
  private String fingerprint;

  @Enumerated(EnumType.STRING)
  @Column(name = "change_type", nullable = false, length = 64)
  private ChangeType changeType;

  @Column(name = "lesson_date", nullable = false)
  private LocalDate lessonDate;

  @Column(name = "lesson_period_id", nullable = false)
  private long lessonPeriodId;

  @Column(name = "group_id")
  private Long groupId;

  @Column(name = "subject_id")
  private Long subjectId;

  @Column(name = "first_seen_at", nullable = false)
  private Instant firstSeenAt;

  @Column(name = "last_seen_at", nullable = false)
  private Instant lastSeenAt;

  protected ScheduleChangeStateEntity() {}

  ScheduleChangeStateEntity(
      TrackingScopeEntity scope,
      String changeKey,
      String fingerprint,
      ChangeType changeType,
      LocalDate lessonDate,
      long lessonPeriodId,
      Long groupId,
      Long subjectId,
      Instant firstSeenAt,
      Instant lastSeenAt) {
    this.scope = scope;
    this.changeKey = changeKey;
    this.fingerprint = fingerprint;
    this.changeType = changeType;
    this.lessonDate = lessonDate;
    this.lessonPeriodId = lessonPeriodId;
    this.groupId = groupId;
    this.subjectId = subjectId;
    this.firstSeenAt = firstSeenAt;
    this.lastSeenAt = lastSeenAt;
  }

  String changeKey() {
    return changeKey;
  }

  String fingerprint() {
    return fingerprint;
  }

  ChangeType changeType() {
    return changeType;
  }

  LocalDate lessonDate() {
    return lessonDate;
  }

  long lessonPeriodId() {
    return lessonPeriodId;
  }

  Long groupId() {
    return groupId;
  }

  Long subjectId() {
    return subjectId;
  }

  Instant firstSeenAt() {
    return firstSeenAt;
  }

  Instant lastSeenAt() {
    return lastSeenAt;
  }
}
