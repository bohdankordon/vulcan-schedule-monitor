ALTER TABLE monitoring_subscription
    RENAME COLUMN journal_id TO legacy_journal_id;

ALTER TABLE monitoring_subscription
    ALTER COLUMN legacy_journal_id DROP NOT NULL;

ALTER TABLE monitoring_subscription
    ADD COLUMN catalog_class_id BIGINT;

UPDATE monitoring_subscription subscription
SET catalog_class_id = match.catalog_class_id,
    legacy_journal_id = NULL
FROM (
    SELECT subscription_match.id AS subscription_id,
           MIN(catalog.id) AS catalog_class_id
    FROM monitoring_subscription subscription_match
    JOIN vulcan_account account
      ON account.app_user_id = subscription_match.app_user_id
    JOIN vulcan_class_catalog catalog
      ON catalog.vulcan_account_id = account.id
     AND catalog.journal_id = subscription_match.legacy_journal_id
    GROUP BY subscription_match.id
    HAVING COUNT(*) = 1
) match
WHERE subscription.id = match.subscription_id;

UPDATE monitoring_subscription
SET enabled = FALSE
WHERE catalog_class_id IS NULL;

ALTER TABLE monitoring_subscription
    DROP CONSTRAINT uq_monitoring_subscription_user_journal;

DROP INDEX ix_monitoring_subscription_enabled_journal;

ALTER TABLE monitoring_subscription
    ADD CONSTRAINT fk_monitoring_subscription_catalog_class
        FOREIGN KEY (catalog_class_id) REFERENCES vulcan_class_catalog (id);

ALTER TABLE monitoring_subscription
    ADD CONSTRAINT uq_monitoring_subscription_user_catalog
        UNIQUE (app_user_id, catalog_class_id);

ALTER TABLE monitoring_subscription
    ADD CONSTRAINT ck_monitoring_subscription_enabled_catalog CHECK (
        NOT enabled OR catalog_class_id IS NOT NULL
    );

CREATE INDEX ix_monitoring_subscription_enabled_catalog
    ON monitoring_subscription (catalog_class_id, app_user_id)
    WHERE enabled;

ALTER TABLE tracking_scope
    ADD COLUMN catalog_class_id BIGINT;

ALTER TABLE tracking_scope
    ADD CONSTRAINT fk_tracking_scope_catalog_class
        FOREIGN KEY (catalog_class_id) REFERENCES vulcan_class_catalog (id);

ALTER TABLE tracking_scope
    DROP CONSTRAINT uq_tracking_scope_journal_week;

CREATE UNIQUE INDEX uq_tracking_scope_catalog_week
    ON tracking_scope (catalog_class_id, week_start)
    WHERE catalog_class_id IS NOT NULL;

CREATE UNIQUE INDEX uq_tracking_scope_legacy_journal_week
    ON tracking_scope (journal_id, week_start)
    WHERE catalog_class_id IS NULL;

ALTER TABLE notification_outbox
    ADD COLUMN catalog_class_id BIGINT;

ALTER TABLE notification_outbox
    ADD CONSTRAINT fk_notification_outbox_catalog_class
        FOREIGN KEY (catalog_class_id) REFERENCES vulcan_class_catalog (id);

UPDATE notification_outbox outbox
SET catalog_class_id = match.catalog_class_id
FROM (
    SELECT outbox_match.id AS outbox_id,
           MIN(catalog.id) AS catalog_class_id
    FROM notification_outbox outbox_match
    JOIN vulcan_account account
      ON account.app_user_id = outbox_match.recipient_user_id
    JOIN vulcan_class_catalog catalog
      ON catalog.vulcan_account_id = account.id
     AND catalog.journal_id = outbox_match.journal_id
    WHERE outbox_match.recipient_user_id IS NOT NULL
    GROUP BY outbox_match.id
    HAVING COUNT(*) = 1
) match
WHERE outbox.id = match.outbox_id;

UPDATE notification_outbox
SET status = 'DEAD',
    lease_until = NULL,
    claim_token = NULL,
    delivered_at = NULL,
    last_failure_category = 'UNROUTABLE'
WHERE catalog_class_id IS NULL
  AND status IN ('PENDING', 'IN_FLIGHT');

ALTER TABLE notification_outbox
    DROP CONSTRAINT ck_notification_outbox_actionable_recipient;

ALTER TABLE notification_outbox
    ADD CONSTRAINT ck_notification_outbox_actionable_routing CHECK (
        status NOT IN ('PENDING', 'IN_FLIGHT')
        OR (recipient_user_id IS NOT NULL AND catalog_class_id IS NOT NULL)
    );

CREATE INDEX ix_notification_outbox_catalog_recipient
    ON notification_outbox (catalog_class_id, recipient_user_id, id);
