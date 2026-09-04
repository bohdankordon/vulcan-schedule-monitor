package io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDispatchSummary;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.MutableClock;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.availability.TelegramProviderAvailabilityGate;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TelegramNotificationDispatchSchedulerTest {

  @Test
  void availableGateDispatchesOncePerInvocation() {
    var dispatcher = mock(NotificationOutboxDispatcher.class);
    when(dispatcher.dispatchOnce()).thenReturn(emptySummary());
    var scheduler = scheduler(dispatcher, new TelegramProviderAvailabilityGate(clock()));
    scheduler.dispatch();
    verify(dispatcher).dispatchOnce();
  }

  @Test
  void deferredAndSuspendedGatesDoNotClaim() {
    var deferredDispatcher = mock(NotificationOutboxDispatcher.class);
    var deferredGate = new TelegramProviderAvailabilityGate(clock());
    deferredGate.defer(Duration.ofSeconds(30));
    scheduler(deferredDispatcher, deferredGate).dispatch();
    verifyNoInteractions(deferredDispatcher);

    var suspendedDispatcher = mock(NotificationOutboxDispatcher.class);
    var suspendedGate = new TelegramProviderAvailabilityGate(clock());
    suspendedGate.suspendUntilRestart();
    scheduler(suspendedDispatcher, suspendedGate).dispatch();
    verifyNoInteractions(suspendedDispatcher);
  }

  @Test
  void dispatchResumesAfterGateExpiry() {
    var clock = clock();
    var dispatcher = mock(NotificationOutboxDispatcher.class);
    when(dispatcher.dispatchOnce()).thenReturn(emptySummary());
    var gate = new TelegramProviderAvailabilityGate(clock);
    var scheduler = scheduler(dispatcher, gate);
    gate.defer(Duration.ofSeconds(30));
    scheduler.dispatch();
    clock.advance(Duration.ofSeconds(30));
    scheduler.dispatch();
    verify(dispatcher).dispatchOnce();
  }

  @Test
  void localOverlapGuardSkipsConcurrentInvocation() throws Exception {
    var dispatcher = mock(NotificationOutboxDispatcher.class);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            ignored -> {
              entered.countDown();
              release.await();
              return emptySummary();
            })
        .when(dispatcher)
        .dispatchOnce();
    var scheduler = scheduler(dispatcher, new TelegramProviderAvailabilityGate(clock()));
    var executor = Executors.newSingleThreadExecutor();
    try {
      var first = executor.submit(scheduler::dispatch);
      entered.await(5, TimeUnit.SECONDS);
      scheduler.dispatch();
      release.countDown();
      first.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    verify(dispatcher).dispatchOnce();
  }

  @Test
  void exceptionDoesNotPreventFutureTicks() {
    var dispatcher = mock(NotificationOutboxDispatcher.class);
    doThrow(new IllegalStateException()).doReturn(emptySummary()).when(dispatcher).dispatchOnce();
    var scheduler = scheduler(dispatcher, new TelegramProviderAvailabilityGate(clock()));
    scheduler.dispatch();
    scheduler.dispatch();
    verify(dispatcher, times(2)).dispatchOnce();
  }

  private TelegramNotificationDispatchScheduler scheduler(
      NotificationOutboxDispatcher dispatcher, TelegramProviderAvailabilityGate gate) {
    return new TelegramNotificationDispatchScheduler(dispatcher, gate);
  }

  private MutableClock clock() {
    return new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
  }

  private NotificationDispatchSummary emptySummary() {
    return new NotificationDispatchSummary(0, 0, 0, 0);
  }
}
