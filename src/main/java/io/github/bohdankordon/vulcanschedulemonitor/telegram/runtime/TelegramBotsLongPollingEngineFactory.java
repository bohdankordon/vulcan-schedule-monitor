package io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime;

import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramApiFailureClassifier;
import okhttp3.OkHttpClient;

public final class TelegramBotsLongPollingEngineFactory
    implements TelegramLongPollingEngineFactory {

  @Override
  public TelegramLongPollingEngine create() {
    var executor = TelegramBotsLongPollingEngine.newOwnedExecutor();
    var httpClient = new OkHttpClient();
    try {
      return new TelegramBotsLongPollingEngine(
          executor, httpClient, new TelegramApiFailureClassifier());
    } catch (RuntimeException failure) {
      executor.shutdownNow();
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
      throw failure;
    }
  }
}
