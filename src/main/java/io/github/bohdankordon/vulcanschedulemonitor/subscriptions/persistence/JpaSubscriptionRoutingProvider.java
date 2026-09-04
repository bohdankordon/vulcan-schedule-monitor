package io.github.bohdankordon.vulcanschedulemonitor.subscriptions.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTarget;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTargetProvider;
import io.github.bohdankordon.vulcanschedulemonitor.notification.recipient.NotificationRecipientProvider;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JpaSubscriptionRoutingProvider
    implements MonitoringTargetProvider, NotificationRecipientProvider {

  private final MonitoringSubscriptionRepository repository;

  JpaSubscriptionRoutingProvider(MonitoringSubscriptionRepository repository) {
    this.repository = repository;
  }

  @Override
  @Transactional(readOnly = true)
  public Collection<MonitoringTarget> activeTargets() {
    return repository.findDistinctActiveTargets().stream()
        .map(
            row ->
                new MonitoringTarget(
                    row.getVulcanAccountId(), row.getCatalogClassId(), row.getJournalId()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Long> activeRecipientUserIds(long catalogClassId) {
    return repository.findActiveRecipientUserIds(catalogClassId);
  }
}
