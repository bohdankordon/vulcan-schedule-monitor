package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "vulcan_class_catalog")
class VulcanClassCatalogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "vulcan_account_id", nullable = false)
  private long vulcanAccountId;

  @Column(name = "journal_id", nullable = false)
  private long journalId;

  @Column(name = "class_id", nullable = false)
  private long classId;

  @Column(nullable = false, length = 255)
  private String name;

  @Column(name = "school_unit", length = 255)
  private String schoolUnit;

  private Integer grade;

  @Column(name = "school_year", nullable = false)
  private int schoolYear;

  @Column(name = "valid_from")
  private LocalDate validFrom;

  @Column(name = "valid_to")
  private LocalDate validTo;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "synced_at", nullable = false)
  private Instant syncedAt;

  protected VulcanClassCatalogEntity() {}

  VulcanClassCatalogEntity(long accountId, SchoolClass schoolClass, Instant now) {
    this.vulcanAccountId = accountId;
    this.journalId = schoolClass.journalId();
    update(schoolClass, now);
  }

  void update(SchoolClass schoolClass, Instant now) {
    this.classId = schoolClass.classId();
    this.name = schoolClass.name();
    this.schoolUnit = schoolClass.schoolUnit();
    this.grade = schoolClass.grade();
    this.schoolYear = schoolClass.schoolYear();
    this.validFrom = schoolClass.validFrom();
    this.validTo = schoolClass.validTo();
    this.active = true;
    this.syncedAt = now;
  }

  void deactivate(Instant now) {
    active = false;
    syncedAt = now;
  }

  Long id() {
    return id;
  }

  long accountId() {
    return vulcanAccountId;
  }

  long journalId() {
    return journalId;
  }

  long classId() {
    return classId;
  }

  String name() {
    return name;
  }

  String schoolUnit() {
    return schoolUnit;
  }

  Integer grade() {
    return grade;
  }

  int schoolYear() {
    return schoolYear;
  }

  LocalDate validFrom() {
    return validFrom;
  }

  LocalDate validTo() {
    return validTo;
  }
}
