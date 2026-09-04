package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

public final class TelegramLongPollingSupervisor {

  private final TelegramLongPollingRuntime runtime;

  public TelegramLongPollingSupervisor(TelegramLongPollingRuntime runtime) {
    this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
  }

  @Scheduled(fixedDelayString = "PT1S", initialDelayString = "PT1S")
  public void supervise() {
    runtime.tryStartIfDue();
  }
}
