package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.CatalogClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.VulcanClassCatalog;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.JpaVulcanConnectTokenService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.secret.SecretDecryptionException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectLink;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.RawConnectToken;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "vulcan.connection.enabled=true",
      "vulcan.connection.public-base-url=http://localhost:8080/",
      "vulcan.connection.token-ttl=PT10M",
      "vulcan.connection.max-credential-attempts=2",
      "vulcan.connection.master-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    })
@Import(VulcanConnectionPostgresTests.Fakes.class)
class VulcanConnectionPostgresTests extends PostgresIntegrationTestSupport {

  private static final Instant NOW = Instant.parse("2026-09-04T10:00:00Z");
  private static final URI APP = URI.create("https://school.vulcan.net.pl/tenant/unit/");

  @Autowired private JdbcTemplate jdbc;
  @Autowired private TelegramIdentityRegistration identities;
  @Autowired private JpaVulcanConnectTokenService tokens;
  @Autowired private VulcanConnectionService connections;
  @Autowired private VulcanClassCatalog catalog;
  @Autowired private VulcanSessionManager sessions;
  @Autowired private VulcanConnectionStatusService statuses;
  @Autowired private ApplicationContext context;
  @Autowired private Fakes fakes;
  @Autowired private MutableClock clock;

  @BeforeEach
  void reset() {
    clearDatabase();
    clock.set(NOW);
    fakes.mode.set(null);
    fakes.classes.set(List.of(schoolClass(7001, 8001, "Class B")));
    fakes.barrier = null;
    fakes.authenticationCalls = 0;
    fakes.verifierFailure.set(false);
  }

  @AfterEach
  void cleanup() {
    clearDatabase();
  }

  @Test
  void connectionPersistsOnlyHashAndEncryptedSessionWithoutCredentialsByDefault() {
    long userId = register(1);
    RawConnectToken raw = issue(userId);
    char[] password = "plain-password-marker".toCharArray();

    ConnectOutcome outcome =
        connections.connect(
            raw, "https://school.vulcan.net.pl/tenant/", "plain-login-marker", password, false);

    assertThat(outcome).isEqualTo(ConnectOutcome.success(1));
    assertThat(password).containsOnly('\0');
    assertThat(jdbc.queryForMap("SELECT * FROM vulcan_account"))
        .containsEntry("app_user_id", userId)
        .containsEntry("status", "CONNECTED")
        .containsEntry("remember_credentials", false);
    assertThat(jdbc.queryForMap("SELECT * FROM vulcan_connect_token").get("consumed_at"))
        .isEqualTo(Timestamp.from(NOW));
    assertThat((byte[]) jdbc.queryForMap("SELECT * FROM vulcan_connect_token").get("token_hash"))
        .hasSize(32)
        .isNotEqualTo(raw.value().getBytes(StandardCharsets.US_ASCII));
    assertThat(jdbc.queryForMap("SELECT * FROM vulcan_account_secret"))
        .containsEntry("credential_nonce", null)
        .containsEntry("credential_ciphertext", null);
    assertNoPlaintextMarkers();
    assertThat(catalog.listActiveForUser(userId))
        .extracting(CatalogClass::journalId)
        .containsExactly(7001L);
    assertThat(sessions.loadCurrent(accountId()).applicationBaseUri()).isEqualTo(APP);
  }

  @Test
  void enabledContextWiresSecureConnectBeansWithoutLaunchingBrowser() {
    assertThat(context.getBeansOfType(VulcanConnectionService.class)).hasSize(1);
    assertThat(context.getBeansOfType(VulcanSessionManager.class)).hasSize(1);
    assertThat(context.getBean(VulcanBrowserAuthenticator.class)).isNotNull();
    assertThat(fakes.authenticationCalls).isZero();
  }

  @Test
  void generatedTokenContainsExactly256BitsOfEntropyMaterial() {
    String value = issue(register(20)).value();

    assertThat(value).hasSize(43).matches("[A-Za-z0-9_-]+");
    assertThat(Base64.getUrlDecoder().decode(value)).hasSize(32);
  }

  @Test
  void rememberedCredentialsAreOptInEncryptedAndSupportExplicitRecovery() {
    long userId = register(2);

    ConnectOutcome outcome =
        connections.connect(
            issue(userId),
            "https://school.vulcan.net.pl/tenant/",
            "plain-login-marker",
            "plain-password-marker".toCharArray(),
            true);

    assertThat(outcome.status()).isEqualTo(ConnectOutcome.Status.SUCCESS);
    assertThat(jdbc.queryForMap("SELECT * FROM vulcan_account_secret"))
        .doesNotContainEntry("credential_nonce", null)
        .doesNotContainEntry("credential_ciphertext", null);
    assertNoPlaintextMarkers();
    assertThat(sessions.recover(accountId()))
        .isEqualTo(VulcanSessionManager.RecoveryResult.RECOVERED);
  }

  @Test
  void recoveryWithoutRememberedCredentialsRequiresUserReconnect() {
    long userId = register(21);
    assertThat(connect(issue(userId)).status()).isEqualTo(ConnectOutcome.Status.SUCCESS);

    assertThat(sessions.recover(accountId()))
        .isEqualTo(VulcanSessionManager.RecoveryResult.RECONNECT_REQUIRED);
    assertThat(statuses.statusForUser(userId).state())
        .isEqualTo(VulcanConnectionStatus.State.RECONNECT_REQUIRED);
  }

  @Test
  void corruptedExistingCiphertextFailsClosedWithoutReplacementOrTokenConsumption() {
    long userId = register(23);
    assertThat(connect(issue(userId)).status()).isEqualTo(ConnectOutcome.Status.SUCCESS);
    jdbc.update(
        "UPDATE vulcan_account_secret SET session_ciphertext = set_byte(session_ciphertext, 0, get_byte(session_ciphertext, 0) # 1)");
    byte[] corrupted =
        (byte[])
            jdbc.queryForMap("SELECT session_ciphertext FROM vulcan_account_secret")
                .get("session_ciphertext");
    RawConnectToken second = issue(userId);

    assertThatThrownBy(() -> connect(second)).isInstanceOf(SecretDecryptionException.class);

    assertThat(
            (byte[])
                jdbc.queryForMap("SELECT session_ciphertext FROM vulcan_account_secret")
                    .get("session_ciphertext"))
        .isEqualTo(corrupted);
    assertThat(
            jdbc.queryForObject(
                "SELECT consumed_at FROM vulcan_connect_token ORDER BY id DESC LIMIT 1",
                Timestamp.class))
        .isNull();
  }

  @Test
  void invalidCredentialsConsumeBudgetButTransientFailuresDoNot() {
    long userId = register(3);
    RawConnectToken raw = issue(userId);
    fakes.mode.set(VulcanAuthFailureCategory.TRANSIENT);

    assertThat(connect(raw).status()).isEqualTo(ConnectOutcome.Status.TRANSIENT_FAILURE);
    assertThat(failedAttempts()).isZero();

    fakes.mode.set(VulcanAuthFailureCategory.INVALID_CREDENTIALS);
    ConnectOutcome first = connect(raw);
    ConnectOutcome second = connect(raw);

    assertThat(first.retryAllowed()).isTrue();
    assertThat(second.retryAllowed()).isFalse();
    assertThat(failedAttempts()).isEqualTo(2);
    assertThat(connect(raw).status()).isEqualTo(ConnectOutcome.Status.TOKEN_INVALID);
  }

  @Test
  void expiredConsumedAndWrongTokensAreRejectedBeforeBrowserCall() {
    long userId = register(4);
    RawConnectToken expired = issue(userId);
    clock.set(NOW.plusSeconds(601));

    assertThat(connect(expired).status()).isEqualTo(ConnectOutcome.Status.TOKEN_INVALID);
    assertThat(connect(new RawConnectToken("___________________________________________")).status())
        .isEqualTo(ConnectOutcome.Status.TOKEN_INVALID);
    assertThat(fakes.authenticationCalls).isZero();

    clock.set(NOW);
    RawConnectToken valid = issue(userId);
    assertThat(connect(valid).status()).isEqualTo(ConnectOutcome.Status.SUCCESS);
    assertThat(connect(valid).status()).isEqualTo(ConnectOutcome.Status.TOKEN_INVALID);
  }

  @Test
  void concurrentCompletionOfOneTokenCommitsExactlyOnce() throws Exception {
    long userId = register(5);
    RawConnectToken raw = issue(userId);
    fakes.barrier = new CyclicBarrier(2);

    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<ConnectOutcome> first = executor.submit(() -> connect(raw));
      Future<ConnectOutcome> second = executor.submit(() -> connect(raw));

      assertThat(List.of(first.get().status(), second.get().status()))
          .containsExactlyInAnyOrder(
              ConnectOutcome.Status.SUCCESS, ConnectOutcome.Status.TOKEN_INVALID);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vulcan_account", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vulcan_account_secret", Integer.class))
        .isOne();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vulcan_class_catalog", Integer.class))
        .isOne();
  }

  @Test
  void completeCatalogSyncUpdatesAndDeactivatesWithinAccountOnly() {
    long firstUser = register(6);
    long secondUser = register(7);
    fakes.classes.set(
        List.of(schoolClass(7001, 8001, "Class B"), schoolClass(7002, 8002, "Class A")));
    assertThat(connect(issue(firstUser)).status()).isEqualTo(ConnectOutcome.Status.SUCCESS);
    assertThat(connect(issue(secondUser)).status()).isEqualTo(ConnectOutcome.Status.SUCCESS);

    fakes.classes.set(List.of(schoolClass(7001, 8999, "Class C")));
    assertThat(connect(issue(firstUser)).status()).isEqualTo(ConnectOutcome.Status.SUCCESS);

    assertThat(catalog.listActiveForUser(firstUser))
        .extracting(CatalogClass::name, CatalogClass::classId)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("Class C", 8999L));
    assertThat(catalog.listActiveForUser(secondUser))
        .extracting(CatalogClass::journalId)
        .containsExactly(7002L, 7001L);
    assertThat(
            jdbc.queryForObject(
                "SELECT active FROM vulcan_class_catalog WHERE vulcan_account_id = ? AND journal_id = 7002",
                Boolean.class,
                accountIdFor(firstUser)))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM vulcan_class_catalog WHERE journal_id = 7001", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void failedDiscoveryDoesNotDeactivateCatalogRows() {
    long userId = register(22);
    fakes.classes.set(
        List.of(schoolClass(7001, 8001, "Class B"), schoolClass(7002, 8002, "Class A")));
    assertThat(connect(issue(userId)).status()).isEqualTo(ConnectOutcome.Status.SUCCESS);
    fakes.verifierFailure.set(true);

    assertThat(connect(issue(userId)).status()).isEqualTo(ConnectOutcome.Status.PROTOCOL_FAILURE);

    assertThat(catalog.listActiveForUser(userId))
        .extracting(CatalogClass::journalId)
        .containsExactly(7002L, 7001L);
  }

  private ConnectOutcome connect(RawConnectToken raw) {
    return connections.connect(
        raw,
        "https://school.vulcan.net.pl/tenant/",
        "synthetic-login",
        "synthetic-password".toCharArray(),
        false);
  }

  private RawConnectToken issue(long userId) {
    ConnectLink link = tokens.issue(userId);
    assertThat(link.enabled()).isTrue();
    String path = URI.create(link.url()).getPath();
    return new RawConnectToken(path.substring(path.lastIndexOf('/') + 1));
  }

  private long register(int suffix) {
    return identities.registerOrUpdate(8_100_000_000L + suffix, 9_100_000_000L + suffix).id();
  }

  private int failedAttempts() {
    return jdbc.queryForObject("SELECT failed_attempts FROM vulcan_connect_token", Integer.class);
  }

  private long accountId() {
    return jdbc.queryForObject("SELECT id FROM vulcan_account", Long.class);
  }

  private long accountIdFor(long userId) {
    return jdbc.queryForObject(
        "SELECT id FROM vulcan_account WHERE app_user_id = ?", Long.class, userId);
  }

  private void assertNoPlaintextMarkers() {
    List<byte[]> secretValues =
        jdbc.query(
            "SELECT session_nonce, session_ciphertext, credential_nonce, credential_ciphertext FROM vulcan_account_secret",
            (row, index) -> {
              byte[] combined = new byte[0];
              for (int column = 1; column <= 4; column++) {
                byte[] value = row.getBytes(column);
                if (value != null) {
                  byte[] expanded =
                      java.util.Arrays.copyOf(combined, combined.length + value.length);
                  System.arraycopy(value, 0, expanded, combined.length, value.length);
                  combined = expanded;
                }
              }
              return combined;
            });
    assertThat(new String(secretValues.getFirst(), StandardCharsets.UTF_8))
        .doesNotContain(
            "plain-login-marker",
            "plain-password-marker",
            "synthetic-cookie",
            "synthetic-verification");
  }

  private void clearDatabase() {
    jdbc.update("DELETE FROM vulcan_class_catalog");
    jdbc.update("DELETE FROM vulcan_account_secret");
    jdbc.update("DELETE FROM vulcan_connect_token");
    jdbc.update("DELETE FROM vulcan_account");
    jdbc.update("DELETE FROM notification_outbox");
    jdbc.update("DELETE FROM monitoring_subscription");
    jdbc.update("DELETE FROM telegram_identity");
    jdbc.update("DELETE FROM app_user");
  }

  private static SchoolClass schoolClass(long journalId, long classId, String name) {
    return new SchoolClass(
        journalId,
        classId,
        name,
        "UNIT",
        2,
        2026,
        LocalDate.of(2026, 9, 1),
        LocalDate.of(2027, 6, 30));
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {
    private final AtomicReference<VulcanAuthFailureCategory> mode = new AtomicReference<>();
    private final AtomicReference<List<SchoolClass>> classes = new AtomicReference<>();
    private final AtomicBoolean verifierFailure = new AtomicBoolean();
    private volatile CyclicBarrier barrier;
    private int authenticationCalls;

    @Bean
    @Primary
    VulcanBrowserAuthenticator fakeBrowserAuthenticator() {
      return request -> {
        synchronized (this) {
          authenticationCalls++;
        }
        CyclicBarrier current = barrier;
        if (current != null) {
          try {
            current.await();
          } catch (Exception exception) {
            throw new AssertionError(exception);
          }
        }
        VulcanAuthFailureCategory failure = mode.get();
        if (failure != null) {
          throw new VulcanAuthenticationException(failure);
        }
        return new VulcanSessionMaterial(
            APP, APP, "synthetic-verification", "synthetic-guid", "unknown=synthetic-cookie");
      };
    }

    @Bean
    @Primary
    VulcanSessionVerifier fakeSessionVerifier() {
      return material -> {
        if (verifierFailure.get()) {
          throw new VulcanAuthenticationException(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
        }
        return classes.get();
      };
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
      return new MutableClock(NOW);
    }
  }

  static final class MutableClock extends Clock {
    private volatile Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
