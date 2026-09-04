package io.github.bohdankordon.vulcanschedulemonitor.monitoring.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ActiveChangeState;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ActiveChangeStore;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeMetadata;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ConcurrentScopeInitializationException;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingState;
import java.util.Objects;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class JpaActiveChangeStore implements ActiveChangeStore {

  private final TrackingScopeRepository scopeRepository;
  private final ScheduleChangeStateRepository changeRepository;

  JpaActiveChangeStore(
      TrackingScopeRepository scopeRepository, ScheduleChangeStateRepository changeRepository) {
    this.scopeRepository = scopeRepository;
    this.changeRepository = changeRepository;
  }

  @Override
  public TrackingState lockOrCreate(TrackingScope scope) {
    Objects.requireNonNull(scope, "scope must not be null");
    TrackingScopeEntity entity =
        scopeRepository
            .findForUpdate(scope.journalId(), scope.weekStart())
            .map(existing -> requireMatchingWeekEnd(existing, scope))
            .orElseGet(() -> createScope(scope));
    var activeChanges =
        changeRepository.findAllByScopeIdOrderByChangeKey(entity.id()).stream()
            .map(JpaActiveChangeStore::toDomain)
            .toList();
    return new TrackingState(
        scope, entity.baselineEstablished(), entity.lastSuccessAt(), activeChanges);
  }

  @Override
  public void save(TrackingState state) {
    TrackingScopeEntity scope =
        scopeRepository
            .findForUpdate(state.scope().journalId(), state.scope().weekStart())
            .map(existing -> requireMatchingWeekEnd(existing, state.scope()))
            .orElseThrow(() -> new IllegalStateException("Locked tracking scope no longer exists"));
    scope.recordSuccessfulReconciliation(state.lastSuccessfulReconciliation());

    changeRepository.deleteAllByScopeId(scope.id());
    changeRepository.flush();
    var replacements =
        state.activeChanges().stream().map(active -> toEntity(scope, active)).toList();
    changeRepository.saveAll(replacements);
  }

  private TrackingScopeEntity createScope(TrackingScope scope) {
    try {
      return scopeRepository.saveAndFlush(
          new TrackingScopeEntity(scope.journalId(), scope.weekStart(), scope.weekEnd()));
    } catch (DataIntegrityViolationException exception) {
      throw new ConcurrentScopeInitializationException(exception);
    }
  }

  private static TrackingScopeEntity requireMatchingWeekEnd(
      TrackingScopeEntity entity, TrackingScope requested) {
    if (!entity.weekEnd().equals(requested.weekEnd())) {
      throw new IllegalStateException("Persisted tracking scope has a conflicting week end");
    }
    return entity;
  }

  private static ActiveChangeState toDomain(ScheduleChangeStateEntity entity) {
    return new ActiveChangeState(
        entity.changeKey(),
        entity.fingerprint(),
        new ChangeMetadata(
            entity.changeType(),
            entity.lessonDate(),
            entity.lessonPeriodId(),
            entity.groupId(),
            entity.subjectId()),
        entity.firstSeenAt(),
        entity.lastSeenAt());
  }

  private static ScheduleChangeStateEntity toEntity(
      TrackingScopeEntity scope, ActiveChangeState active) {
    ChangeMetadata metadata = active.metadata();
    return new ScheduleChangeStateEntity(
        scope,
        active.changeKey(),
        active.fingerprint(),
        metadata.changeType(),
        metadata.lessonDate(),
        metadata.lessonPeriodId(),
        metadata.groupId(),
        metadata.subjectId(),
        active.firstSeenAt(),
        active.lastSeenAt());
  }
}
