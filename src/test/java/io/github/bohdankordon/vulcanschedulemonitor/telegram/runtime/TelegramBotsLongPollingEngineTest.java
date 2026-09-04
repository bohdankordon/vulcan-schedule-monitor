package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelegramBotsLongPollingEngineTest {

  @Test
  void closingProductionWrapperShutsDownOwnedExecutorWithoutNetwork() {
    var engine = new TelegramBotsLongPollingEngine();
    engine.close();
    assertThat(engine.executorShutdown()).isTrue();
  }
}
