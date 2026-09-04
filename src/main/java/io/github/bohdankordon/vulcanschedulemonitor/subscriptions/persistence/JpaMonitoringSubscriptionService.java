package io.github.bohdankordon.vulcanschedulemonitor.subscriptions.persistence;

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
  public MonitoringSubscription enable(long appUserId, long journalId) {
    Instant now = clock.instant();
    MonitoringSubscriptionEntity entity =
        repository
            .findByAppUserIdAndJournalId(appUserId, journalId)
            .map(
                existing -> {
                  existing.setEnabled(true, now);
                  return existing;
                })
            .orElseGet(
                () -> repository.save(new MonitoringSubscriptionEntity(appUserId, journalId, now)));
    return toModel(entity);
  }

  @Override
  @Transactional
  public void disable(long appUserId, long journalId) {
    repository
        .findByAppUserIdAndJournalId(appUserId, journalId)
        .ifPresent(subscription -> subscription.setEnabled(false, clock.instant()));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> activeJournalIds(long appUserId) {
    return repository.findAllByAppUserIdAndEnabledTrueOrderByJournalId(appUserId).stream()
        .map(MonitoringSubscriptionEntity::journalId)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isSubscribed(long appUserId, long journalId) {
    return repository.existsByAppUserIdAndJournalIdAndEnabledTrue(appUserId, journalId);
  }

  private static MonitoringSubscription toModel(MonitoringSubscriptionEntity entity) {
    return new MonitoringSubscription(
        entity.id(),
        entity.appUserId(),
        entity.journalId(),
        entity.enabled(),
        entity.createdAt(),
        entity.updatedAt());
  }
}
