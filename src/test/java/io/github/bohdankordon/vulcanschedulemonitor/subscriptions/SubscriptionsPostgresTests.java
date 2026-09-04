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

    MonitoringSubscription created = subscriptions.enable(userId, 42L);
    MonitoringSubscription unchanged = subscriptions.enable(userId, 42L);
    assertThat(unchanged.id()).isEqualTo(created.id());
    assertThat(subscriptions.isSubscribed(userId, 42L)).isTrue();

    clock.setInstant(SECOND);
    subscriptions.disable(userId, 42L);
    assertThat(subscriptions.isSubscribed(userId, 42L)).isFalse();
    assertThat(jdbc.queryForMap("SELECT * FROM monitoring_subscription"))
        .containsEntry("enabled", false)
        .containsEntry("updated_at", Timestamp.from(SECOND));

    MonitoringSubscription reenabled = subscriptions.enable(userId, 42L);
    assertThat(reenabled.id()).isEqualTo(created.id());
    assertThat(reenabled.enabled()).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_subscription", Integer.class))
        .isOne();
  }

  @Test
  void usersMaySubscribeAcrossJournalsAndActiveListingIsDeterministic() {
    long firstUser = register(20);
    long secondUser = register(21);

    subscriptions.enable(firstUser, 300L);
    subscriptions.enable(firstUser, 100L);
    subscriptions.enable(firstUser, 200L);
    subscriptions.enable(secondUser, 100L);

    assertThat(subscriptions.activeJournalIds(firstUser)).containsExactly(100L, 200L, 300L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM monitoring_subscription", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void targetProviderReturnsDistinctSortedActiveJournalsOnly() {
    assertThat(targetProvider.activeTargets()).isEmpty();
    long firstUser = register(30);
    long secondUser = register(31);
    long inactiveUser = register(32);

    subscriptions.enable(firstUser, 300L);
    subscriptions.enable(firstUser, 100L);
    subscriptions.enable(secondUser, 100L);
    subscriptions.enable(secondUser, 200L);
    subscriptions.disable(secondUser, 200L);
    subscriptions.enable(inactiveUser, 400L);
    jdbc.update("UPDATE app_user SET active = FALSE WHERE id = ?", inactiveUser);

    assertThat(targetProvider.activeTargets())
        .extracting(MonitoringTarget::journalId)
        .containsExactly(100L, 300L);
  }

  @Test
  void recipientProviderReturnsDistinctSortedInternalActiveUserIdsOnly() {
    long firstUser = register(40);
    long secondUser = register(41);
    long disabledUser = register(42);
    long inactiveUser = register(43);
    long unrelatedUser = register(44);
    subscriptions.enable(secondUser, 500L);
    subscriptions.enable(firstUser, 500L);
    subscriptions.enable(disabledUser, 500L);
    subscriptions.disable(disabledUser, 500L);
    subscriptions.enable(inactiveUser, 500L);
    subscriptions.enable(unrelatedUser, 501L);
    jdbc.update("UPDATE app_user SET active = FALSE WHERE id = ?", inactiveUser);

    assertThat(recipientProvider.activeRecipientUserIds(500L))
        .containsExactly(firstUser, secondUser)
        .allMatch(id -> id < 8_000_000_000L);
  }

  private long register(int suffix) {
    return registration.registerOrUpdate(8_000_000_000L + suffix, 9_000_000_000L + suffix).id();
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
