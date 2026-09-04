package io.github.bohdankordon.vulcanschedulemonitor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDeliveryGateway;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationDeliveryGateway;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationDispatchScheduler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramLongPollingEngine;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramLongPollingEngineFactory;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramLongPollingRuntime;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramTransportException;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;

@SpringBootTest(
    properties = {
      "telegram.bot.enabled=true",
      "telegram.bot.token=synthetic-test-token",
      "telegram.dispatch.initial-delay=PT1H"
    })
@Import(TelegramEnabledApplicationTests.Fakes.class)
class TelegramEnabledApplicationTests extends PostgresIntegrationTestSupport {

  @Autowired private ApplicationContext context;

  @Test
  void enabledAdapterWiresAgainstNoNetworkFakes() {
    assertThat(context.getBean(TelegramMessageTransport.class))
        .isSameAs(context.getBean(Fakes.class).transport);
    assertThat(context.getBeansOfType(TelegramLongPollingRuntime.class)).hasSize(1);
    assertThat(context.getBeansOfType(TelegramNotificationDeliveryGateway.class)).hasSize(1);
    assertThat(context.getBeansOfType(NotificationDeliveryGateway.class)).hasSize(1);
    assertThat(context.getBeansOfType(NotificationOutboxDispatcher.class)).hasSize(1);
    assertThat(context.getBeansOfType(TelegramNotificationDispatchScheduler.class)).hasSize(1);
    assertThat(context.getBeansOfType(TelegramIdentityRegistration.class)).hasSize(1);
    assertThat(context.getBean(Fakes.class).networkCalls).hasValue(0);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Fakes {
    private final AtomicInteger networkCalls = new AtomicInteger();
    private final TelegramMessageTransport transport =
        (chat, text) -> networkCalls.incrementAndGet();
    private final TelegramLongPollingEngineFactory engineFactory =
        () ->
            new TelegramLongPollingEngine() {
              @Override
              public void start(String token, LongPollingUpdateConsumer consumer)
                  throws TelegramTransportException {}

              @Override
              public void close() {}
            };

    @Bean
    @Primary
    TelegramMessageTransport fakeTelegramMessageTransport() {
      return transport;
    }

    @Bean
    @Primary
    TelegramLongPollingEngineFactory fakeTelegramLongPollingEngineFactory() {
      return engineFactory;
    }
  }
}
