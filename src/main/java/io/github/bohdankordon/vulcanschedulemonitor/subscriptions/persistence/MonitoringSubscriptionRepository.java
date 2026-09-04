package io.github.bohdankordon.vulcanschedulemonitor.subscriptions.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MonitoringSubscriptionRepository
    extends JpaRepository<MonitoringSubscriptionEntity, Long> {

  interface CatalogSelectionRow {
    Long getCatalogClassId();

    String getClassName();

    String getSchoolUnit();

    Integer getSchoolYear();

    Boolean getSubscribed();
  }

  interface MonitoringTargetRow {
    Long getVulcanAccountId();

    Long getCatalogClassId();

    Long getJournalId();
  }

  Optional<MonitoringSubscriptionEntity> findByAppUserIdAndCatalogClassId(
      long appUserId, long catalogClassId);

  boolean existsByAppUserIdAndCatalogClassIdAndEnabledTrue(long appUserId, long catalogClassId);

  @Query(
      value =
          """
          SELECT catalog.id AS catalogClassId,
                 catalog.name AS className,
                 catalog.school_unit AS schoolUnit,
                 catalog.school_year AS schoolYear,
                 COALESCE(subscription.enabled, FALSE) AS subscribed
          FROM vulcan_account account
          JOIN vulcan_class_catalog catalog ON catalog.vulcan_account_id = account.id
          LEFT JOIN monitoring_subscription subscription
            ON subscription.app_user_id = account.app_user_id
           AND subscription.catalog_class_id = catalog.id
          WHERE account.app_user_id = :appUserId
            AND account.status = 'CONNECTED'
            AND catalog.active
          ORDER BY LOWER(catalog.name), catalog.name,
                   catalog.school_unit NULLS LAST, catalog.school_year, catalog.id
          """,
      nativeQuery = true)
  List<CatalogSelectionRow> findAvailableClasses(@Param("appUserId") long appUserId);

  @Query(
      value =
          """
          SELECT catalog.id AS catalogClassId,
                 catalog.name AS className,
                 catalog.school_unit AS schoolUnit,
                 catalog.school_year AS schoolYear,
                 COALESCE(subscription.enabled, FALSE) AS subscribed
          FROM vulcan_account account
          JOIN vulcan_class_catalog catalog ON catalog.vulcan_account_id = account.id
          LEFT JOIN monitoring_subscription subscription
            ON subscription.app_user_id = account.app_user_id
           AND subscription.catalog_class_id = catalog.id
          WHERE account.app_user_id = :appUserId
            AND account.status = 'CONNECTED'
            AND catalog.active
            AND catalog.id = :catalogClassId
          """,
      nativeQuery = true)
  Optional<CatalogSelectionRow> findSelectableClass(
      @Param("appUserId") long appUserId, @Param("catalogClassId") long catalogClassId);

  @Query(
      value =
          """
          SELECT EXISTS (
              SELECT 1
              FROM vulcan_account account
              JOIN vulcan_class_catalog catalog ON catalog.vulcan_account_id = account.id
              WHERE account.app_user_id = :appUserId
                AND catalog.id = :catalogClassId
          )
          """,
      nativeQuery = true)
  boolean isCatalogOwnedByUser(
      @Param("appUserId") long appUserId, @Param("catalogClassId") long catalogClassId);

  @Query(
      value =
          """
          SELECT DISTINCT account.id AS vulcanAccountId,
                          catalog.id AS catalogClassId,
                          catalog.journal_id AS journalId
          FROM monitoring_subscription subscription
          JOIN app_user app_user ON app_user.id = subscription.app_user_id
          JOIN vulcan_class_catalog catalog ON catalog.id = subscription.catalog_class_id
          JOIN vulcan_account account
            ON account.id = catalog.vulcan_account_id
           AND account.app_user_id = subscription.app_user_id
          WHERE subscription.enabled
            AND app_user.active
            AND catalog.active
            AND account.status = 'CONNECTED'
          ORDER BY account.id, catalog.id, catalog.journal_id
          """,
      nativeQuery = true)
  List<MonitoringTargetRow> findDistinctActiveTargets();

  @Query(
      value =
          """
          SELECT DISTINCT subscription.app_user_id
          FROM monitoring_subscription subscription
          JOIN app_user app_user ON app_user.id = subscription.app_user_id
          JOIN vulcan_class_catalog catalog ON catalog.id = subscription.catalog_class_id
          JOIN vulcan_account account
            ON account.id = catalog.vulcan_account_id
           AND account.app_user_id = subscription.app_user_id
          WHERE subscription.catalog_class_id = :catalogClassId
            AND subscription.enabled
            AND app_user.active
            AND catalog.active
            AND account.status = 'CONNECTED'
          ORDER BY subscription.app_user_id
          """,
      nativeQuery = true)
  List<Long> findActiveRecipientUserIds(@Param("catalogClassId") long catalogClassId);
}
