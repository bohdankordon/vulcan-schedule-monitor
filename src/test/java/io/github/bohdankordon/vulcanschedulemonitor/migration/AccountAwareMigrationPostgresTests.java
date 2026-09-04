package io.github.bohdankordon.vulcanschedulemonitor.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AccountAwareMigrationPostgresTests extends PostgresIntegrationTestSupport {

  private static final String SCHEMA = "phase8_account_aware_migration";
  private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
  private static final LocalDate WEEK_START = LocalDate.of(2026, 9, 7);

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void v5SafelyMigratesKnownOwnershipAndQuarantinesAmbiguousActionableData() {
    jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    jdbc.execute("CREATE SCHEMA " + SCHEMA);
    try {
      Flyway throughV4 = flywayAt("4");
      throughV4.migrate();

      long mappedUser = insertUser();
      long unmappedUser = insertUser();
      long account = insertAccount(mappedUser);
      long catalog = insertCatalog(account, 42, "Synthetic 2A");
      long mappedSubscription = insertSubscription(mappedUser, 42, true);
      long unmappedSubscription = insertSubscription(unmappedUser, 99, true);
      long legacyScope = insertLegacyScope(42);
      long mappedOutbox = insertPending(mappedUser, 42);
      long ambiguousOutbox = insertPending(unmappedUser, 99);

      Flyway throughV5 = flywayAt("5");
      throughV5.migrate();

      assertThat(throughV5.info().current().getVersion().getVersion()).isEqualTo("5");
      assertThat(subscription(mappedSubscription))
          .containsEntry("catalog_class_id", catalog)
          .containsEntry("legacy_journal_id", null)
          .containsEntry("enabled", true);
      assertThat(subscription(unmappedSubscription))
          .containsEntry("catalog_class_id", null)
          .containsEntry("legacy_journal_id", 99L)
          .containsEntry("enabled", false);
      assertThat(
              jdbc.queryForMap(
                  "SELECT catalog_class_id, journal_id FROM "
                      + SCHEMA
                      + ".tracking_scope WHERE id = ?",
                  legacyScope))
          .containsEntry("catalog_class_id", null)
          .containsEntry("journal_id", 42L);
      assertThat(outbox(mappedOutbox))
          .containsEntry("catalog_class_id", catalog)
          .containsEntry("status", "PENDING");
      assertThat(outbox(ambiguousOutbox))
          .containsEntry("catalog_class_id", null)
          .containsEntry("status", "DEAD")
          .containsEntry("last_failure_category", "UNROUTABLE")
          .containsEntry("lease_until", null)
          .containsEntry("claim_token", null);

      long secondUser = insertUser();
      long secondAccount = insertAccount(secondUser);
      long secondCatalog = insertCatalog(secondAccount, 42, "Synthetic 2B");
      assertThat(insertAccountAwareScope(catalog, 42)).isPositive();
      assertThat(insertAccountAwareScope(secondCatalog, 42)).isPositive();
      assertThatThrownBy(() -> insertPendingAfterV5(mappedUser, null))
          .isInstanceOf(DataIntegrityViolationException.class);
      assertThat(insertPendingAfterV5(mappedUser, catalog)).isPositive();
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
  }

  private Flyway flywayAt(String target) {
    return Flyway.configure()
        .dataSource(dataSource)
        .schemas(SCHEMA)
        .defaultSchema(SCHEMA)
        .target(MigrationVersion.fromVersion(target))
        .load();
  }

  private long insertUser() {
    return jdbc.queryForObject(
        "INSERT INTO "
            + SCHEMA
            + ".app_user (active, created_at, updated_at) VALUES (TRUE, ?, ?) RETURNING id",
        Long.class,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private long insertAccount(long userId) {
    return jdbc.queryForObject(
        "INSERT INTO "
            + SCHEMA
            + ".vulcan_account "
            + "(app_user_id, status, remember_credentials, created_at, updated_at, authenticated_at) "
            + "VALUES (?, 'CONNECTED', FALSE, ?, ?, ?) RETURNING id",
        Long.class,
        userId,
        Timestamp.from(NOW),
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private long insertCatalog(long accountId, long journalId, String name) {
    return jdbc.queryForObject(
        "INSERT INTO "
            + SCHEMA
            + ".vulcan_class_catalog "
            + "(vulcan_account_id, journal_id, class_id, name, school_year, active, synced_at) "
            + "VALUES (?, ?, ?, ?, 2026, TRUE, ?) RETURNING id",
        Long.class,
        accountId,
        journalId,
        journalId + 100,
        name,
        Timestamp.from(NOW));
  }

  private long insertSubscription(long userId, long journalId, boolean enabled) {
    return jdbc.queryForObject(
        "INSERT INTO "
            + SCHEMA
            + ".monitoring_subscription "
            + "(app_user_id, journal_id, enabled, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?) RETURNING id",
        Long.class,
        userId,
        journalId,
        enabled,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private long insertLegacyScope(long journalId) {
    return jdbc.queryForObject(
        "INSERT INTO "
            + SCHEMA
            + ".tracking_scope "
            + "(journal_id, week_start, week_end, baseline_established, version) "
            + "VALUES (?, ?, ?, FALSE, 0) RETURNING id",
        Long.class,
        journalId,
        WEEK_START,
        WEEK_START.plusDays(6));
  }

  private long insertAccountAwareScope(long catalogId, long journalId) {
    return jdbc.queryForObject(
        "INSERT INTO "
            + SCHEMA
            + ".tracking_scope "
            + "(catalog_class_id, journal_id, week_start, week_end, baseline_established, version) "
            + "VALUES (?, ?, ?, ?, FALSE, 0) RETURNING id",
        Long.class,
        catalogId,
        journalId,
        WEEK_START,
        WEEK_START.plusDays(6));
  }

  private long insertPending(long recipientUserId, long journalId) {
    return jdbc.queryForObject(
        "INSERT INTO "
            + SCHEMA
            + ".notification_outbox "
            + "(event_type, journal_id, week_start, week_end, active_change_count, "
            + "recipient_user_id, status, attempt_count, next_attempt_at, created_at) "
            + "VALUES ('BASELINE_ESTABLISHED', ?, ?, ?, 0, ?, 'PENDING', 0, ?, ?) RETURNING id",
        Long.class,
        journalId,
        WEEK_START,
        WEEK_START.plusDays(6),
        recipientUserId,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private long insertPendingAfterV5(long recipientUserId, Long catalogId) {
    return jdbc.queryForObject(
        "INSERT INTO "
            + SCHEMA
            + ".notification_outbox "
            + "(event_type, journal_id, catalog_class_id, week_start, week_end, active_change_count, "
            + "recipient_user_id, status, attempt_count, next_attempt_at, created_at) "
            + "VALUES ('BASELINE_ESTABLISHED', 42, ?, ?, ?, 0, ?, 'PENDING', 0, ?, ?) RETURNING id",
        Long.class,
        catalogId,
        WEEK_START,
        WEEK_START.plusDays(6),
        recipientUserId,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private java.util.Map<String, Object> subscription(long id) {
    return jdbc.queryForMap(
        "SELECT catalog_class_id, legacy_journal_id, enabled FROM "
            + SCHEMA
            + ".monitoring_subscription WHERE id = ?",
        id);
  }

  private java.util.Map<String, Object> outbox(long id) {
    return jdbc.queryForMap(
        "SELECT catalog_class_id, status, last_failure_category, lease_until, claim_token FROM "
            + SCHEMA
            + ".notification_outbox WHERE id = ?",
        id);
  }
}
