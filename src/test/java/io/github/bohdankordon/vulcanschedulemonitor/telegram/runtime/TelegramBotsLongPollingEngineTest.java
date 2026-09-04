package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramApiFailureClassifier;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.TelegramUrl;

class TelegramBotsLongPollingEngineTest {

  private static final String TOKEN = "123456:synthetic-test-token";
  private static final String BOT_PATH = "/bot" + TOKEN + "/";
  private static final LongPollingUpdateConsumer CONSUMER = updates -> {};

  private WireMockServer server;
  private TelegramBotsLongPollingEngine engine;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterEach
  void closeResources() throws InterruptedException {
    if (engine != null) {
      engine.close();
      engine.awaitExecutorTermination(Duration.ofSeconds(2));
    }
    if (server != null && server.isRunning()) {
      server.stop();
    }
  }

  @Test
  void structuredUnauthorizedPreflightIsAuthenticationAndSkipsRegistration() {
    stubProbe(
        401,
        """
        {"ok":false,"error_code":401,"description":"Synthetic unauthorized response"}
        """);

    TelegramTransportException failure =
        assertThrows(TelegramTransportException.class, () -> newEngine().start(TOKEN, CONSUMER));

    assertThat(failure.category()).isEqualTo(TelegramFailureCategory.AUTHENTICATION);
    assertThat(failure.retryAfter()).isEmpty();
    assertThat(failure).hasMessage("Telegram operation failed: AUTHENTICATION").hasNoCause();
    verifyProbeOnly();
  }

  @Test
  void structuredRateLimitPreflightPreservesRetryAfterAndSkipsRegistration() {
    stubProbe(
        429,
        """
        {"ok":false,"error_code":429,"description":"Synthetic rate limit",
         "parameters":{"retry_after":37}}
        """);

    TelegramTransportException failure =
        assertThrows(TelegramTransportException.class, () -> newEngine().start(TOKEN, CONSUMER));

    assertThat(failure.category()).isEqualTo(TelegramFailureCategory.RATE_LIMITED);
    assertThat(failure.retryAfter()).contains(Duration.ofSeconds(37));
    verifyProbeOnly();
  }

  @Test
  void structuredRateLimitWithoutRetryAfterUsesConservativeFallback() {
    stubProbe(
        429,
        """
        {"ok":false,"error_code":429,"description":"Synthetic rate limit"}
        """);

    TelegramTransportException failure =
        assertThrows(TelegramTransportException.class, () -> newEngine().start(TOKEN, CONSUMER));

    assertThat(failure.category()).isEqualTo(TelegramFailureCategory.RATE_LIMITED);
    assertThat(failure.retryAfter()).contains(Duration.ofSeconds(30));
    verifyProbeOnly();
  }

  @Test
  void structuredServerFailurePreflightIsTransient() {
    stubProbe(
        503,
        """
        {"ok":false,"error_code":503,"description":"Synthetic unavailable response"}
        """);

    TelegramTransportException failure =
        assertThrows(TelegramTransportException.class, () -> newEngine().start(TOKEN, CONSUMER));

    assertThat(failure.category()).isEqualTo(TelegramFailureCategory.TRANSIENT);
    assertThat(failure.retryAfter()).isEmpty();
    verifyProbeOnly();
  }

  @Test
  void probeTransportFailureIsTransient() {
    int stoppedPort = server.port();
    server.stop();

    TelegramTransportException failure =
        assertThrows(
            TelegramTransportException.class, () -> newEngine(stoppedPort).start(TOKEN, CONSUMER));

    assertThat(failure.category()).isEqualTo(TelegramFailureCategory.TRANSIENT);
    assertThat(failure.retryAfter()).isEmpty();
  }

  @Test
  void successfulPreflightContinuesToRegistrationAndOwnedResourcesClose() throws Exception {
    var pollObserved = new CountDownLatch(1);
    var consumerInterrupted = new AtomicBoolean();
    LongPollingUpdateConsumer blockingConsumer =
        updates -> {
          pollObserved.countDown();
          try {
            new CountDownLatch(1).await();
          } catch (InterruptedException interrupted) {
            consumerInterrupted.set(true);
            Thread.currentThread().interrupt();
          }
        };
    stubSuccessfulProbe();
    server.stubFor(
        post(urlPathEqualTo(BOT_PATH + "deleteWebhook"))
            .willReturn(jsonResponse(200, "{\"ok\":true,\"result\":true}")));
    server.stubFor(
        post(urlPathEqualTo(BOT_PATH + "getupdates"))
            .willReturn(jsonResponse(200, "{\"ok\":true,\"result\":[{\"update_id\":1}]}")));

    newEngine().start(TOKEN, blockingConsumer);

    server.verify(1, postRequestedFor(urlPathEqualTo(BOT_PATH + "getme")));
    server.verify(1, postRequestedFor(urlPathEqualTo(BOT_PATH + "deleteWebhook")));
    assertThat(pollObserved.await(2, TimeUnit.SECONDS)).isTrue();
    engine.close();
    assertThat(engine.executorShutdown()).isTrue();
    assertThat(engine.httpDispatcherShutdown()).isTrue();
    assertThat(engine.awaitExecutorTermination(Duration.ofSeconds(2))).isTrue();
    assertThat(consumerInterrupted).isTrue();
    engine = null;
  }

  @Test
  void unstructuredRegistrationFailureAfterPreflightRemainsTransient() {
    stubSuccessfulProbe();
    server.stubFor(
        post(urlPathEqualTo(BOT_PATH + "deleteWebhook"))
            .willReturn(aResponse().withStatus(401).withBody("Synthetic upstream rejection")));

    TelegramTransportException failure =
        assertThrows(TelegramTransportException.class, () -> newEngine().start(TOKEN, CONSUMER));

    assertThat(failure.category()).isEqualTo(TelegramFailureCategory.TRANSIENT);
    server.verify(1, postRequestedFor(urlPathEqualTo(BOT_PATH + "getme")));
    server.verify(1, postRequestedFor(urlPathEqualTo(BOT_PATH + "deleteWebhook")));
  }

  @Test
  void closingProductionWrapperShutsDownOwnedResourcesWithoutNetwork() throws InterruptedException {
    newEngine().close();

    assertThat(engine.executorShutdown()).isTrue();
    assertThat(engine.httpDispatcherShutdown()).isTrue();
    assertThat(engine.awaitExecutorTermination(Duration.ofSeconds(2))).isTrue();
    engine = null;
  }

  @Test
  void productionLongPollingClientOutlastsActualGetUpdatesTimeout() {
    var getUpdates = TelegramBotsLongPollingEngine.newGetUpdatesGenerator().apply(0);
    var httpClient = TelegramBotsLongPollingEngine.newLongPollingHttpClient();

    assertThat(getUpdates.getTimeout()).isEqualTo(50);
    assertThat(httpClient.readTimeoutMillis())
        .isPositive()
        .isGreaterThan(Math.toIntExact(Duration.ofSeconds(getUpdates.getTimeout()).toMillis()));
    assertThat(httpClient.callTimeoutMillis()).isZero();

    httpClient.dispatcher().executorService().shutdown();
    httpClient.connectionPool().evictAll();
  }

  @Test
  void productionFactoryUsesLongPollingClientAndItsResourcesStillClose()
      throws InterruptedException {
    engine = (TelegramBotsLongPollingEngine) new TelegramBotsLongPollingEngineFactory().create();

    assertThat(engine.httpReadTimeout()).isEqualTo(Duration.ofSeconds(65));
    assertThat(engine.httpCallTimeout()).isZero();

    engine.close();
    assertThat(engine.executorShutdown()).isTrue();
    assertThat(engine.httpDispatcherShutdown()).isTrue();
    assertThat(engine.awaitExecutorTermination(Duration.ofSeconds(2))).isTrue();
    engine = null;
  }

  private TelegramBotsLongPollingEngine newEngine() {
    return newEngine(server.port());
  }

  private TelegramBotsLongPollingEngine newEngine(int port) {
    TelegramUrl telegramUrl = new TelegramUrl("http", "localhost", port, false);
    engine =
        new TelegramBotsLongPollingEngine(
            TelegramBotsLongPollingEngine.newOwnedExecutor(),
            new OkHttpClient(),
            new TelegramApiFailureClassifier(),
            telegramUrl);
    return engine;
  }

  private void stubSuccessfulProbe() {
    stubProbe(
        200,
        """
        {"ok":true,"result":{"id":123456,"is_bot":true,
         "first_name":"Synthetic","username":"synthetic_test_bot"}}
        """);
  }

  private void stubProbe(int status, String body) {
    server.stubFor(post(urlPathEqualTo(BOT_PATH + "getme")).willReturn(jsonResponse(status, body)));
  }

  private void verifyProbeOnly() {
    server.verify(1, postRequestedFor(urlPathEqualTo(BOT_PATH + "getme")));
    server.verify(0, postRequestedFor(urlPathEqualTo(BOT_PATH + "deleteWebhook")));
    server.verify(0, postRequestedFor(urlPathEqualTo(BOT_PATH + "getupdates")));
  }

  private ResponseDefinitionBuilder jsonResponse(int status, String body) {
    return aResponse()
        .withStatus(status)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }
}
