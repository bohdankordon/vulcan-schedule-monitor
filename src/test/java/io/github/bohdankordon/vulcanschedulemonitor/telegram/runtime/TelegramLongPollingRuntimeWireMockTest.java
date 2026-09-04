package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.MutableClock;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailability;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramApiFailureClassifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.meta.TelegramUrl;

class TelegramLongPollingRuntimeWireMockTest {

  private static final String TOKEN = "654321:synthetic-runtime-token";
  private static final String GET_ME_PATH = "/bot" + TOKEN + "/getme";
  private static final String DELETE_WEBHOOK_PATH = "/bot" + TOKEN + "/deleteWebhook";

  private final List<TelegramBotsLongPollingEngine> engines = new ArrayList<>();
  private WireMockServer server;
  private TelegramLongPollingRuntime runtime;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterEach
  void closeResources() throws InterruptedException {
    if (runtime != null) {
      runtime.close();
    }
    for (TelegramBotsLongPollingEngine engine : engines) {
      engine.awaitExecutorTermination(Duration.ofSeconds(2));
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void realUnauthorizedPreflightSuspendsRuntimeAndPreventsAllLaterHttpWork() {
    stubProbe(
        401,
        """
        {"ok":false,"error_code":401,"description":"Synthetic unauthorized response"}
        """);
    var clock = new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
    var gate = new TelegramProviderAvailabilityGate(clock);
    runtime = newRuntime(clock, gate);

    runtime.tryStartIfDue();

    assertThat(runtime.isRunning()).isFalse();
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.SUSPENDED_UNTIL_RESTART);
    server.verify(1, postRequestedFor(urlPathEqualTo(GET_ME_PATH)));
    server.verify(0, postRequestedFor(urlPathEqualTo(DELETE_WEBHOOK_PATH)));
    assertThat(engines).allSatisfy(this::assertClosed);

    clock.advance(Duration.ofDays(1));
    runtime.tryStartIfDue();

    assertThat(runtime.isRunning()).isFalse();
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.SUSPENDED_UNTIL_RESTART);
    server.verify(1, postRequestedFor(urlPathEqualTo(GET_ME_PATH)));
    assertThat(engines).hasSize(1);
  }

  @Test
  void realRateLimitPreflightDefersRuntimeForStructuredRetryAfter() {
    stubProbe(
        429,
        """
        {"ok":false,"error_code":429,"description":"Synthetic rate limit",
         "parameters":{"retry_after":37}}
        """);
    var clock = new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
    var gate = new TelegramProviderAvailabilityGate(clock);
    runtime = newRuntime(clock, gate);

    runtime.tryStartIfDue();

    assertThat(runtime.isRunning()).isFalse();
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.DEFERRED);
    server.verify(1, postRequestedFor(urlPathEqualTo(GET_ME_PATH)));
    server.verify(0, postRequestedFor(urlPathEqualTo(DELETE_WEBHOOK_PATH)));
    assertThat(engines).allSatisfy(this::assertClosed);

    clock.advance(Duration.ofSeconds(36));
    runtime.tryStartIfDue();
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.DEFERRED);
    server.verify(1, postRequestedFor(urlPathEqualTo(GET_ME_PATH)));

    clock.advance(Duration.ofSeconds(2));
    runtime.tryStartIfDue();
    assertThat(runtime.isRunning()).isFalse();
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.DEFERRED);
    server.verify(2, postRequestedFor(urlPathEqualTo(GET_ME_PATH)));
    assertThat(engines).hasSize(2);
    assertThat(engines).allSatisfy(this::assertClosed);
  }

  private TelegramLongPollingRuntime newRuntime(
      MutableClock clock, TelegramProviderAvailabilityGate gate) {
    TelegramUrl telegramUrl = new TelegramUrl("http", "localhost", server.port(), false);
    TelegramLongPollingEngineFactory factory =
        () -> {
          var engine =
              new TelegramBotsLongPollingEngine(
                  TelegramBotsLongPollingEngine.newOwnedExecutor(),
                  new OkHttpClient(),
                  new TelegramApiFailureClassifier(),
                  telegramUrl);
          engines.add(engine);
          return engine;
        };
    LongPollingUpdateConsumer consumer = updates -> {};
    return new TelegramLongPollingRuntime(TOKEN, factory, consumer, gate, clock);
  }

  private void stubProbe(int status, String body) {
    server.stubFor(
        post(urlPathEqualTo(GET_ME_PATH))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private void assertClosed(TelegramBotsLongPollingEngine engine) {
    assertThat(engine.executorShutdown()).isTrue();
    assertThat(engine.httpDispatcherShutdown()).isTrue();
  }
}
