package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.MutableClock;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailability;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;

class TelegramLongPollingRuntimeTest {

  @Test
  void transientFailureClosesPartialEngineAndLaterRetrySucceeds() {
    var clock = new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
    var gate = new TelegramProviderAvailabilityGate(clock);
    var failed =
        new FakeEngine(new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null));
    var successful = new FakeEngine(null);
    var factory = new FakeFactory(failed, successful);
    var runtime = runtime(factory, gate, clock);

    runtime.tryStartIfDue();
    assertThat(runtime.isRunning()).isFalse();
    assertThat(failed.closed).isTrue();

    runtime.tryStartIfDue();
    assertThat(factory.created).isOne();
    clock.advance(Duration.ofSeconds(5));
    runtime.tryStartIfDue();
    assertThat(runtime.isRunning()).isTrue();

    runtime.close();
    assertThat(successful.closed).isTrue();
  }

  @Test
  void authenticationFailureSuspendsAllFurtherRegistrationAttempts() {
    var clock = new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
    var gate = new TelegramProviderAvailabilityGate(clock);
    var failed =
        new FakeEngine(
            new TelegramTransportException(TelegramFailureCategory.AUTHENTICATION, null));
    var factory = new FakeFactory(failed, new FakeEngine(null));
    var runtime = runtime(factory, gate, clock);

    runtime.tryStartIfDue();
    clock.advance(Duration.ofDays(1));
    runtime.tryStartIfDue();

    assertThat(factory.created).isOne();
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.SUSPENDED_UNTIL_RESTART);
  }

  @Test
  void transientRetrySequenceIsBoundedAndCapsAtTwoMinutes() {
    var clock = new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
    var gate = new TelegramProviderAvailabilityGate(clock);
    TelegramTransportException transientFailure =
        new TelegramTransportException(TelegramFailureCategory.TRANSIENT, null);
    var factory =
        new FakeFactory(
            new FakeEngine(transientFailure),
            new FakeEngine(transientFailure),
            new FakeEngine(transientFailure),
            new FakeEngine(transientFailure),
            new FakeEngine(transientFailure),
            new FakeEngine(null));
    var runtime = runtime(factory, gate, clock);

    runtime.tryStartIfDue();
    assertThat(factory.created).isEqualTo(1);
    for (Duration delay :
        java.util.List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(45),
            Duration.ofMinutes(2),
            Duration.ofMinutes(2))) {
      clock.advance(delay.minusMillis(1));
      runtime.tryStartIfDue();
      int beforeDue = factory.created;
      clock.advance(Duration.ofMillis(1));
      runtime.tryStartIfDue();
      assertThat(factory.created).isEqualTo(beforeDue + 1);
    }
    assertThat(runtime.isRunning()).isTrue();
    runtime.close();
  }

  private TelegramLongPollingRuntime runtime(
      TelegramLongPollingEngineFactory factory,
      TelegramProviderAvailabilityGate gate,
      MutableClock clock) {
    LongPollingUpdateConsumer consumer = updates -> {};
    return new TelegramLongPollingRuntime("synthetic-token", factory, consumer, gate, clock);
  }

  private static final class FakeFactory implements TelegramLongPollingEngineFactory {
    private final Queue<TelegramLongPollingEngine> engines = new ArrayDeque<>();
    private int created;

    private FakeFactory(TelegramLongPollingEngine... engines) {
      this.engines.addAll(java.util.List.of(engines));
    }

    @Override
    public TelegramLongPollingEngine create() {
      created++;
      return engines.remove();
    }
  }

  private static final class FakeEngine implements TelegramLongPollingEngine {
    private final TelegramTransportException failure;
    private boolean closed;

    private FakeEngine(TelegramTransportException failure) {
      this.failure = failure;
    }

    @Override
    public void start(String token, LongPollingUpdateConsumer consumer)
        throws TelegramTransportException {
      if (failure != null) {
        throw failure;
      }
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
