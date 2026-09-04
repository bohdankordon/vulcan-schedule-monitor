package io.github.bohdankordon.vulcanschedulemonitor.subscriptions.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringClassSelection;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscription;
import io.github.bohdankordon.vulcanschedulemonitor.subscriptions.MonitoringSubscriptionService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaMonitoringSubscriptionService implements MonitoringSubscriptionService {

  private final MonitoringSubscriptionRepository repository;
  private final Clock clock;

  JpaMonitoringSubscriptionService(MonitoringSubscriptionRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Override
  @Transactional
  public MonitoringSubscription enable(long appUserId, long catalogClassId) {
    requirePositive(appUserId, catalogClassId);
    var selected =
        repository
            .findSelectableClass(appUserId, catalogClassId)
            .orElseThrow(() -> new IllegalArgumentException("Catalog class is not selectable"));
    Instant now = clock.instant();
    MonitoringSubscriptionEntity entity =
        repository
            .findByAppUserIdAndCatalogClassId(appUserId, catalogClassId)
            .map(
                existing -> {
                  existing.setEnabled(true, now);
                  return existing;
                })
            .orElseGet(
                () ->
                    repository.save(
                        new MonitoringSubscriptionEntity(appUserId, catalogClassId, now)));
    return toSubscription(entity, selected);
  }

  @Override
  @Transactional
  public void disable(long appUserId, long catalogClassId) {
    requirePositive(appUserId, catalogClassId);
    if (!repository.isCatalogOwnedByUser(appUserId, catalogClassId)) {
      return;
    }
    repository
        .findByAppUserIdAndCatalogClassId(appUserId, catalogClassId)
        .ifPresent(subscription -> subscription.setEnabled(false, clock.instant()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<MonitoringSubscription> activeSubscriptions(long appUserId) {
    return repository.findAvailableClasses(appUserId).stream()
        .filter(row -> Boolean.TRUE.equals(row.getSubscribed()))
        .map(
            row -> {
              MonitoringSubscriptionEntity entity =
                  repository
                      .findByAppUserIdAndCatalogClassId(appUserId, row.getCatalogClassId())
                      .orElseThrow();
              return toSubscription(entity, row);
            })
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<MonitoringClassSelection> availableClasses(long appUserId) {
    return repository.findAvailableClasses(appUserId).stream()
        .map(JpaMonitoringSubscriptionService::toSelection)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isSubscribed(long appUserId, long catalogClassId) {
    return repository.existsByAppUserIdAndCatalogClassIdAndEnabledTrue(appUserId, catalogClassId);
  }

  private static MonitoringSubscription toSubscription(
      MonitoringSubscriptionEntity entity,
      MonitoringSubscriptionRepository.CatalogSelectionRow selected) {
    return new MonitoringSubscription(
        entity.id(),
        entity.appUserId(),
        entity.catalogClassId(),
        selected.getClassName(),
        selected.getSchoolUnit(),
        selected.getSchoolYear(),
        entity.enabled(),
        entity.createdAt(),
        entity.updatedAt());
  }

  private static MonitoringClassSelection toSelection(
      MonitoringSubscriptionRepository.CatalogSelectionRow row) {
    return new MonitoringClassSelection(
        row.getCatalogClassId(),
        row.getClassName(),
        row.getSchoolUnit(),
        row.getSchoolYear(),
        Boolean.TRUE.equals(row.getSubscribed()));
  }

  private static void requirePositive(long appUserId, long catalogClassId) {
    if (appUserId <= 0 || catalogClassId <= 0) {
      throw new IllegalArgumentException("Application user and catalog class ids must be positive");
    }
  }
}
