package io.github.bohdankordon.vulcanschedulemonitor.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTarget;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTargetProvider;
import io.github.bohdankordon.vulcanschedulemonitor.notification.recipient.NotificationRecipientProvider;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.users.ApplicationUser;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramRecipientDirectory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(SubscriptionsPostgresTests.TestClockConfiguration.class)
class SubscriptionsPostgresTests extends PostgresIntegrationTestSupport {

  private static final Instant FIRST = Instant.parse("2026-09-04T10:15:30Z");
  private static final Instant SECOND = Instant.parse("2026-09-04T11:45:00Z");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TelegramIdentityRegistration registration;
  @Autowired private MonitoringSubscriptionService subscriptions;
  @Autowired private MonitoringTargetProvider targetProvider;
  @Autowired private NotificationRecipientProvider recipientProvider;
  @Autowired private TelegramRecipientDirectory recipientDirectory;
  @Autowired private MutableClock clock;

  @BeforeEach
  void clearDatabase() {
    jdbc.update("DELETE FROM notification_outbox");
    jdbc.update("DELETE FROM monitoring_subscription");
    jdbc.update("DELETE FROM tracking_scope");
    jdbc.update("DELETE FROM vulcan_class_catalog");
    jdbc.update("DELETE FROM vulcan_account_secret");
    jdbc.update("DELETE FROM vulcan_connect_token");
    jdbc.update("DELETE FROM vulcan_account");
    jdbc.update("DELETE FROM telegram_identity");
    jdbc.update("DELETE FROM app_user");
    clock.setInstant(FIRST);
  }

  @Test
  void unknownTelegramIdentityCreatesOneMinimalApplicationUserAndIdentity() {
    ApplicationUser user = registration.registerOrUpdate(8_000_000_001L, 9_000_000_001L);

    assertThat(user.active()).isTrue();
    assertThat(user.createdAt()).isEqualTo(FIRST);
    assertThat(user.updatedAt()).isEqualTo(FIRST);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isOne();
    assertThat(jdbc.queryForMap("SELECT * FROM telegram_identity"))
        .containsEntry("app_user_id", user.id())
        .containsEntry("telegram_user_id", 8_000_000_001L)
        .containsEntry("private_chat_id", 9_000_000_001L)
        .containsEntry("created_at", Timestamp.from(FIRST))
        .containsEntry("updated_at", Timestamp.from(FIRST));
    assertThat(recipientDirectory.findByAppUserId(user.id()))
        .hasValueSatisfying(
            recipient -> {
              assertThat(recipient.telegramUserId()).isEqualTo(8_000_000_001L);
              assertThat(recipient.privateChatId()).isEqualTo(9_000_000_001L);
              assertThat(recipient.getClass().getName()).doesNotContain("persistence");
            });
  }

  @Test
  void registrationReusesUserUpdatesChangedChatAndReactivatesExplicitly() {
    ApplicationUser first = registration.registerOrUpdate(8_000_000_002L, 9_000_000_002L);
    jdbc.update("UPDATE app_user SET active = FALSE WHERE id = ?", first.id());
    clock.setInstant(SECOND);

    ApplicationUser updated = registration.registerOrUpdate(8_000_000_002L, 9_000_000_102L);

    assertThat(updated.id()).isEqualTo(first.id());
    assertThat(updated.active()).isTrue();
    assertThat(updated.createdAt()).isEqualTo(FIRST);
    assertThat(updated.updatedAt()).isEqualTo(SECOND);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isOne();
    assertThat(jdbc.queryForMap("SELECT * FROM telegram_identity"))
        .containsEntry("private_chat_id", 9_000_000_102L)
        .containsEntry("created_at", Timestamp.from(FIRST))
        .containsEntry("updated_at", Timestamp.from(SECOND));
  }

  @Test
  void databaseEnforcesUniqueTelegramUserAndPrivateChatIds() {
    ApplicationUser first = registration.registerOrUpdate(8_000_000_003L, 9_000_000_003L);
    long secondUserId = insertUser();

    assertThatThrownBy(() -> insertIdentity(secondUserId, 8_000_000_003L, 9_000_000_004L))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> insertIdentity(secondUserId, 8_000_000_004L, 9_000_000_003L))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM telegram_identity", Integer.class))
        .isOne();
    assertThat(first.id()).isPositive();
  }

  @Test
  void persistedTelegramIdentityContainsOnlyRequiredRoutingDataAndTimestamps() {
    registration.registerOrUpdate(8_000_000_005L, 9_000_000_005L);

    List<String> columns =
        jdbc.queryForList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = 'telegram_identity'
            ORDER BY ordinal_position
            """,
            String.class);

    assertThat(columns)
        .containsExactly(
            "app_user_id", "telegram_user_id", "private_chat_id", "created_at", "updated_at")
        .doesNotContain("username", "first_name", "last_name", "message_text", "locale");
  }

  @Test
  void enablingDisablingAndReenablingSubscriptionIsIdempotent() {
    long userId = register(10);
    long catalogClassId = connectAndAddClass(userId, 42L, "Synthetic 2A");

    MonitoringSubscription created = subscriptions.enable(userId, catalogClassId);
    MonitoringSubscription unchanged = subscriptions.enable(userId, catalogClassId);
    assertThat(unchanged.id()).isEqualTo(created.id());
    assertThat(created.className()).isEqualTo("Synthetic 2A");
    assertThat(subscriptions.isSubscribed(userId, catalogClassId)).isTrue();

    clock.setInstant(SECOND);
    subscriptions.disable(userId, catalogClassId);
    assertThat(subscriptions.isSubscribed(userId, catalogClassId)).isFalse();
    assertThat(jdbc.queryForMap("SELECT * FROM monitoring_subscription"))
        .containsEntry("enabled", false)
        .containsEntry("updated_at", Timestamp.from(SECOND));

    MonitoringSubscription reenabled = subscriptions.enable(userId, catalogClassId);
    assertThat(reenabled.id()).isEqualTo(created.id());
    assertThat(reenabled.enabled()).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_subscription", Integer.class))
        .isOne();
  }

  @Test
  void usersMaySelectOwnedClassesAndActiveListingIsDeterministic() {
    long firstUser = register(20);
    long secondUser = register(21);
    long firstC = connectAndAddClass(firstUser, 300L, "Synthetic C");
    long firstA = addClass(firstUser, 100L, "Synthetic A");
    long firstB = addClass(firstUser, 200L, "Synthetic B");
    long secondA = connectAndAddClass(secondUser, 100L, "Synthetic A");

    subscriptions.enable(firstUser, firstC);
    subscriptions.enable(firstUser, firstA);
    subscriptions.enable(firstUser, firstB);
    subscriptions.enable(secondUser, secondA);

    assertThat(subscriptions.activeSubscriptions(firstUser))
        .extracting(MonitoringSubscription::className)
        .containsExactly("Synthetic A", "Synthetic B", "Synthetic C");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_subscription", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void targetProviderKeepsSameJournalClassesAccountScopedAndFiltersInactiveState() {
    assertThat(targetProvider.activeTargets()).isEmpty();
    long firstUser = register(30);
    long secondUser = register(31);
    long inactiveUser = register(32);
    long first300 = connectAndAddClass(firstUser, 300L, "Synthetic 3A");
    long first100 = addClass(firstUser, 100L, "Synthetic 1A");
    long second100 = connectAndAddClass(secondUser, 100L, "Synthetic 1B");
    long second200 = addClass(secondUser, 200L, "Synthetic 2B");
    long inactive400 = connectAndAddClass(inactiveUser, 400L, "Synthetic 4A");

    subscriptions.enable(firstUser, first300);
    subscriptions.enable(firstUser, first100);
    subscriptions.enable(secondUser, second100);
    subscriptions.enable(secondUser, second200);
    subscriptions.disable(secondUser, second200);
    subscriptions.enable(inactiveUser, inactive400);
    jdbc.update("UPDATE app_user SET active = FALSE WHERE id = ?", inactiveUser);

    assertThat(targetProvider.activeTargets())
        .extracting(MonitoringTarget::journalId)
        .containsExactly(300L, 100L, 100L);
    assertThat(targetProvider.activeTargets())
        .extracting(MonitoringTarget::catalogClassId)
        .containsExactly(first300, first100, second100);

    jdbc.update(
        "UPDATE vulcan_account SET status = 'RECONNECT_REQUIRED' WHERE app_user_id = ?", firstUser);
    jdbc.update("UPDATE vulcan_class_catalog SET active = FALSE WHERE id = ?", second100);
    assertThat(targetProvider.activeTargets()).isEmpty();
  }

  @Test
  void recipientProviderRoutesByOwnedCatalogClassNotSharedJournal() {
    long firstUser = register(40);
    long secondUser = register(41);
    long firstCatalog = connectAndAddClass(firstUser, 500L, "Synthetic 5A");
    long secondCatalog = connectAndAddClass(secondUser, 500L, "Synthetic 5B");
    subscriptions.enable(firstUser, firstCatalog);
    subscriptions.enable(secondUser, secondCatalog);

    assertThat(recipientProvider.activeRecipientUserIds(firstCatalog))
        .containsExactly(firstUser)
        .allMatch(id -> id < 8_000_000_000L);
    assertThat(recipientProvider.activeRecipientUserIds(secondCatalog)).containsExactly(secondUser);
  }

  @Test
  void crossUserAndInactiveCatalogSelectionIsRejectedWithoutMutation() {
    long firstUser = register(50);
    long secondUser = register(51);
    long firstCatalog = connectAndAddClass(firstUser, 600L, "Synthetic 6A");
    long secondCatalog = connectAndAddClass(secondUser, 600L, "Synthetic 6B");

    assertThatThrownBy(() -> subscriptions.enable(firstUser, secondCatalog))
        .isInstanceOf(IllegalArgumentException.class);
    jdbc.update("UPDATE vulcan_class_catalog SET active = FALSE WHERE id = ?", firstCatalog);
    assertThatThrownBy(() -> subscriptions.enable(firstUser, firstCatalog))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_subscription", Integer.class))
        .isZero();
  }

  private long register(int suffix) {
    return registration.registerOrUpdate(8_000_000_000L + suffix, 9_000_000_000L + suffix).id();
  }

  private long connectAndAddClass(long appUserId, long journalId, String name) {
    long accountId =
        jdbc.queryForObject(
            """
            INSERT INTO vulcan_account
              (app_user_id, status, remember_credentials, created_at, updated_at, authenticated_at)
            VALUES (?, 'CONNECTED', FALSE, ?, ?, ?)
            RETURNING id
            """,
            Long.class,
            appUserId,
            Timestamp.from(FIRST),
            Timestamp.from(FIRST),
            Timestamp.from(FIRST));
    return insertCatalogClass(accountId, journalId, name);
  }

  private long addClass(long appUserId, long journalId, String name) {
    long accountId =
        jdbc.queryForObject(
            "SELECT id FROM vulcan_account WHERE app_user_id = ?", Long.class, appUserId);
    return insertCatalogClass(accountId, journalId, name);
  }

  private long insertCatalogClass(long accountId, long journalId, String name) {
    return jdbc.queryForObject(
        """
        INSERT INTO vulcan_class_catalog
          (vulcan_account_id, journal_id, class_id, name, school_year, active, synced_at)
        VALUES (?, ?, ?, ?, 2026, TRUE, ?)
        RETURNING id
        """,
        Long.class,
        accountId,
        journalId,
        journalId + 10_000,
        name,
        Timestamp.from(FIRST));
  }

  private long insertUser() {
    return jdbc.queryForObject(
        """
        INSERT INTO app_user (active, created_at, updated_at)
        VALUES (TRUE, ?, ?)
        RETURNING id
        """,
        Long.class,
        Timestamp.from(FIRST),
        Timestamp.from(FIRST));
  }

  private void insertIdentity(long appUserId, long telegramUserId, long privateChatId) {
    jdbc.update(
        """
        INSERT INTO telegram_identity
          (app_user_id, telegram_user_id, private_chat_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        appUserId,
        telegramUserId,
        privateChatId,
        Timestamp.from(FIRST),
        Timestamp.from(FIRST));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClockConfiguration {

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(FIRST);
    }
  }

  static final class MutableClock extends Clock {

    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) {
        throw new IllegalArgumentException("Test clock only supports UTC");
      }
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
