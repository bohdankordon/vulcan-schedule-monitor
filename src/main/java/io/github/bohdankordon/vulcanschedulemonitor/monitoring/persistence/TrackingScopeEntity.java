package io.github.bohdankordon.vulcanschedulemonitor.monitoring.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "tracking_scope")
class TrackingScopeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "catalog_class_id")
  private Long catalogClassId;

  @Column(name = "journal_id", nullable = false)
  private long journalId;

  @Column(name = "week_start", nullable = false)
  private LocalDate weekStart;

  @Column(name = "week_end", nullable = false)
  private LocalDate weekEnd;

  @Column(name = "baseline_established", nullable = false)
  private boolean baselineEstablished;

  @Column(name = "last_success_at")
  private Instant lastSuccessAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected TrackingScopeEntity() {}

  TrackingScopeEntity(long catalogClassId, long journalId, LocalDate weekStart, LocalDate weekEnd) {
    this.catalogClassId = catalogClassId;
    this.journalId = journalId;
    this.weekStart = weekStart;
    this.weekEnd = weekEnd;
  }

  Long id() {
    return id;
  }

  Long catalogClassId() {
    return catalogClassId;
  }

  long journalId() {
    return journalId;
  }

  LocalDate weekStart() {
    return weekStart;
  }

  LocalDate weekEnd() {
    return weekEnd;
  }

  boolean baselineEstablished() {
    return baselineEstablished;
  }

  Instant lastSuccessAt() {
    return lastSuccessAt;
  }

  void recordSuccessfulReconciliation(Instant at) {
    baselineEstablished = true;
    lastSuccessAt = at;
  }
}
