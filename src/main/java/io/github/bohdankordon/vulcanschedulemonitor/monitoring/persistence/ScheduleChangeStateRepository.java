package io.github.bohdankordon.vulcanschedulemonitor.monitoring.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ScheduleChangeStateRepository extends JpaRepository<ScheduleChangeStateEntity, Long> {

  List<ScheduleChangeStateEntity> findAllByScopeIdOrderByChangeKey(long scopeId);

  void deleteAllByScopeId(long scopeId);
}
