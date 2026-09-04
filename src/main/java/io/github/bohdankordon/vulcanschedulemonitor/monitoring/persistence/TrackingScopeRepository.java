package io.github.bohdankordon.vulcanschedulemonitor.monitoring.persistence;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TrackingScopeRepository extends JpaRepository<TrackingScopeEntity, Long> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select scope from TrackingScopeEntity scope
      where scope.catalogClassId = :catalogClassId and scope.weekStart = :weekStart
      """)
  Optional<TrackingScopeEntity> findForUpdate(
      @Param("catalogClassId") long catalogClassId, @Param("weekStart") LocalDate weekStart);

  Optional<TrackingScopeEntity> findByCatalogClassIdAndWeekStart(
      long catalogClassId, LocalDate weekStart);
}
