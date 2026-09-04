package io.github.bohdankordon.vulcanschedulemonitor.telegram.availability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TelegramProviderAvailabilityGateTest {

  @Test
  void deferralExpiresAndLongerDeferralWins() {
    var clock = new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
    var gate = new TelegramProviderAvailabilityGate(clock);

    gate.defer(Duration.ofSeconds(30));
    gate.defer(Duration.ofSeconds(10));
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.DEFERRED);

    clock.advance(Duration.ofSeconds(30));
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.AVAILABLE);
  }

  @Test
  void suspensionDoesNotExpireWithinProcess() {
    var clock = new MutableClock(Instant.parse("2026-09-04T10:00:00Z"));
    var gate = new TelegramProviderAvailabilityGate(clock);
    gate.suspendUntilRestart();
    clock.advance(Duration.ofDays(100));
    assertThat(gate.availability()).isEqualTo(TelegramProviderAvailability.SUSPENDED_UNTIL_RESTART);
  }
}
