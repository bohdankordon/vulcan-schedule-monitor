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
class LegacyOutboxMigrationPostgresTests extends PostgresIntegrationTestSupport {

  private static final String SCHEMA = "phase5_legacy_migration";
  private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
  private static final LocalDate WEEK_START = LocalDate.of(2026, 9, 7);

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void v3QuarantinesLegacyActionableRowsAndEnforcesRecipientRouting() {
    jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    jdbc.execute("CREATE SCHEMA " + SCHEMA);
    try {
      Flyway throughV2 =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(SCHEMA)
              .defaultSchema(SCHEMA)
              .target(MigrationVersion.fromVersion("2"))
              .load();
      throughV2.migrate();
      assertThat(throughV2.info().current().getVersion().getVersion()).isEqualTo("2");

      long legacyId =
          jdbc.queryForObject(
              """
              INSERT INTO phase5_legacy_migration.notification_outbox
                (event_type, journal_id, week_start, week_end, active_change_count, status,
                 attempt_count, next_attempt_at, created_at)
              VALUES ('BASELINE_ESTABLISHED', 42, ?, ?, 0, 'PENDING', 0, ?, ?)
              RETURNING id
              """,
              Long.class,
              WEEK_START,
              WEEK_START.plusDays(6),
              Timestamp.from(NOW),
              Timestamp.from(NOW));

      Flyway throughV3 =
          Flyway.configure()
              .dataSource(dataSource)
              .schemas(SCHEMA)
              .defaultSchema(SCHEMA)
              .target(MigrationVersion.fromVersion("3"))
              .load();
      throughV3.migrate();

      assertThat(throughV3.info().current().getVersion().getVersion()).isEqualTo("3");
      assertThat(
              jdbc.queryForMap(
                  """
                  SELECT status, recipient_user_id, lease_until, claim_token,
                         delivered_at, last_failure_category
                  FROM phase5_legacy_migration.notification_outbox
                  WHERE id = ?
                  """,
                  legacyId))
          .containsEntry("status", "DEAD")
          .containsEntry("recipient_user_id", null)
          .containsEntry("lease_until", null)
          .containsEntry("claim_token", null)
          .containsEntry("delivered_at", null)
          .containsEntry("last_failure_category", "UNROUTABLE");

      assertThat(
              jdbc.queryForObject(
                  """
                  SELECT count(*)
                  FROM information_schema.columns
                  WHERE table_schema = ?
                    AND table_name = 'notification_outbox'
                    AND column_name = 'recipient_user_id'
                  """,
                  Integer.class,
                  SCHEMA))
          .isOne();
      assertThat(constraintDefinition("fk_notification_outbox_recipient_user"))
          .contains("FOREIGN KEY (recipient_user_id)")
          .contains("app_user(id)");
      assertThat(constraintDefinition("ck_notification_outbox_actionable_recipient"))
          .contains("recipient_user_id IS NOT NULL")
          .contains("PENDING")
          .contains("IN_FLIGHT");

      assertThatThrownBy(() -> insertPending(null))
          .isInstanceOf(DataIntegrityViolationException.class);
      long recipientUserId = insertUser();
      assertThat(insertPending(recipientUserId)).isPositive();
      assertThatThrownBy(() -> insertPending(Long.MAX_VALUE))
          .isInstanceOf(DataIntegrityViolationException.class);
    } finally {
      jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
  }

  private String constraintDefinition(String constraintName) {
    return jdbc.queryForObject(
        """
        SELECT pg_get_constraintdef(c.oid)
        FROM pg_constraint c
        JOIN pg_namespace namespace ON namespace.oid = c.connamespace
        WHERE namespace.nspname = ? AND c.conname = ?
        """,
        String.class,
        SCHEMA,
        constraintName);
  }

  private long insertUser() {
    return jdbc.queryForObject(
        """
        INSERT INTO phase5_legacy_migration.app_user (active, created_at, updated_at)
        VALUES (TRUE, ?, ?)
        RETURNING id
        """,
        Long.class,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private long insertPending(Long recipientUserId) {
    return jdbc.queryForObject(
        """
        INSERT INTO phase5_legacy_migration.notification_outbox
          (event_type, journal_id, week_start, week_end, active_change_count,
           recipient_user_id, status, attempt_count, next_attempt_at, created_at)
        VALUES ('BASELINE_ESTABLISHED', 42, ?, ?, 0, ?, 'PENDING', 0, ?, ?)
        RETURNING id
        """,
        Long.class,
        WEEK_START,
        WEEK_START.plusDays(6),
        recipientUserId,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }
}
