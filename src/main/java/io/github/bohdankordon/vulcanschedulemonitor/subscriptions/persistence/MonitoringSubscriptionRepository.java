package io.github.bohdankordon.vulcanschedulemonitor.subscriptions.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MonitoringSubscriptionRepository
    extends JpaRepository<MonitoringSubscriptionEntity, Long> {

  Optional<MonitoringSubscriptionEntity> findByAppUserIdAndJournalId(
      long appUserId, long journalId);

  boolean existsByAppUserIdAndJournalIdAndEnabledTrue(long appUserId, long journalId);

  List<MonitoringSubscriptionEntity> findAllByAppUserIdAndEnabledTrueOrderByJournalId(
      long appUserId);

  @Query(
      value =
          """
          SELECT DISTINCT subscription.journal_id
          FROM monitoring_subscription subscription
          JOIN app_user app_user ON app_user.id = subscription.app_user_id
          WHERE subscription.enabled AND app_user.active
          ORDER BY subscription.journal_id
          """,
      nativeQuery = true)
  List<Long> findDistinctActiveJournalIds();

  @Query(
      value =
          """
          SELECT DISTINCT subscription.app_user_id
          FROM monitoring_subscription subscription
          JOIN app_user app_user ON app_user.id = subscription.app_user_id
          WHERE subscription.journal_id = :journalId
            AND subscription.enabled
            AND app_user.active
          ORDER BY subscription.app_user_id
          """,
      nativeQuery = true)
  List<Long> findActiveRecipientUserIds(@Param("journalId") long journalId);
}
