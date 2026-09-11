package io.github.bohdankordon.vulcanschedulemonitor.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TelegramLanguageMigrationPostgresTests extends PostgresIntegrationTestSupport {

  private static final String SCHEMA = "telegram_language_migration";
  private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

  @Autowired private DataSource dataSource;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void v6AddsLanguageCodeWithDefaultEnAndValidatesSupportedCodes() {
    jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    jdbc.execute("CREATE SCHEMA " + SCHEMA);
    try {
      Flyway throughV5 = flywayAt("5");
      throughV5.migrate();
      assertThat(throughV5.info().current().getVersion().getVersion()).isEqualTo("5");

      long preV6User = insertUser();
      long preV6TelegramUser = 1111L;
      long preV6PrivateChat = 2222L;
      insertIdentityV5(preV6User, preV6TelegramUser, preV6PrivateChat);

      Flyway throughV6 = flywayAt("6");
      throughV6.migrate();
      assertThat(throughV6.info().current().getVersion().getVersion()).isEqualTo("6");

      // 1. Existing identity row receives language_code='en'
      var existingRow = queryIdentity(preV6User);
      assertThat(existingRow)
          .containsEntry("app_user_id", preV6User)
          .containsEntry("telegram_user_id", preV6TelegramUser)
          .containsEntry("private_chat_id", preV6PrivateChat)
          .containsEntry("language_code", "en");

      // 2. Newly inserted row without language_code defaults to 'en'
      long newUser = insertUser();
      long newTelegramUser = 3333L;
      long newPrivateChat = 4444L;
      insertIdentityDefaultLanguage(newUser, newTelegramUser, newPrivateChat);
      var newRow = queryIdentity(newUser);
      assertThat(newRow).containsEntry("language_code", "en");

      // 3. Valid language codes accepted: en, ru, uk, pl
      long userRu = insertUser();
      insertIdentityWithLanguage(userRu, 5555L, 6666L, "ru");
      assertThat(queryIdentity(userRu)).containsEntry("language_code", "ru");

      long userUk = insertUser();
      insertIdentityWithLanguage(userUk, 7777L, 8888L, "uk");
      assertThat(queryIdentity(userUk)).containsEntry("language_code", "uk");

      long userPl = insertUser();
      insertIdentityWithLanguage(userPl, 9999L, 1010L, "pl");
      assertThat(queryIdentity(userPl)).containsEntry("language_code", "pl");

      // 4. Unsupported language codes rejected by CHECK constraint: ua, de, arbitrary
      long userInvalidUa = insertUser();
      assertThatThrownBy(() -> insertIdentityWithLanguage(userInvalidUa, 1112L, 1113L, "ua"))
          .isInstanceOf(DataIntegrityViolationException.class);

      long userInvalidDe = insertUser();
      assertThatThrownBy(() -> insertIdentityWithLanguage(userInvalidDe, 1114L, 1115L, "de"))
          .isInstanceOf(DataIntegrityViolationException.class);

      long userInvalidArbitrary = insertUser();
      assertThatThrownBy(
              () -> insertIdentityWithLanguage(userInvalidArbitrary, 1116L, 1117L, "xyz"))
          .isInstanceOf(DataIntegrityViolationException.class);

      // 5. User/chat identity values remain unchanged during language update
      jdbc.update(
          "UPDATE " + SCHEMA + ".telegram_identity SET language_code = 'pl' WHERE app_user_id = ?",
          preV6User);
      var updatedRow = queryIdentity(preV6User);
      assertThat(updatedRow)
          .containsEntry("app_user_id", preV6User)
          .containsEntry("telegram_user_id", preV6TelegramUser)
          .containsEntry("private_chat_id", preV6PrivateChat)
          .containsEntry("language_code", "pl");

      // 6. Rollback compatibility: older application queries (without language_code) work
      var legacyRow =
          jdbc.queryForMap(
              "SELECT app_user_id, telegram_user_id, private_chat_id, created_at, updated_at FROM "
                  + SCHEMA
                  + ".telegram_identity WHERE app_user_id = ?",
              preV6User);
      assertThat(legacyRow)
          .containsEntry("app_user_id", preV6User)
          .containsEntry("telegram_user_id", preV6TelegramUser)
          .containsEntry("private_chat_id", preV6PrivateChat);
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

  private void insertIdentityV5(long appUserId, long telegramUserId, long privateChatId) {
    jdbc.update(
        "INSERT INTO "
            + SCHEMA
            + ".telegram_identity (app_user_id, telegram_user_id, private_chat_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?)",
        appUserId,
        telegramUserId,
        privateChatId,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private void insertIdentityDefaultLanguage(
      long appUserId, long telegramUserId, long privateChatId) {
    jdbc.update(
        "INSERT INTO "
            + SCHEMA
            + ".telegram_identity (app_user_id, telegram_user_id, private_chat_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?)",
        appUserId,
        telegramUserId,
        privateChatId,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private void insertIdentityWithLanguage(
      long appUserId, long telegramUserId, long privateChatId, String languageCode) {
    jdbc.update(
        "INSERT INTO "
            + SCHEMA
            + ".telegram_identity (app_user_id, telegram_user_id, private_chat_id, language_code, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        appUserId,
        telegramUserId,
        privateChatId,
        languageCode,
        Timestamp.from(NOW),
        Timestamp.from(NOW));
  }

  private java.util.Map<String, Object> queryIdentity(long appUserId) {
    return jdbc.queryForMap(
        "SELECT app_user_id, telegram_user_id, private_chat_id, language_code FROM "
            + SCHEMA
            + ".telegram_identity WHERE app_user_id = ?",
        appUserId);
  }
}
