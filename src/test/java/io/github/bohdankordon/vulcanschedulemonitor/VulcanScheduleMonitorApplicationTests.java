package io.github.bohdankordon.vulcanschedulemonitor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringScheduler;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTargetProvider;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDeliveryGateway;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationDeliveryGateway;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.delivery.TelegramNotificationDispatchScheduler;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramLongPollingRuntime;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.runtime.TelegramLongPollingSupervisor;
import io.github.bohdankordon.vulcanschedulemonitor.telegram.transport.TelegramMessageTransport;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanBrowserAuthenticator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanSessionManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class VulcanScheduleMonitorApplicationTests extends PostgresIntegrationTestSupport {

  @Autowired private Flyway flyway;
  @Autowired private ApplicationContext context;

  @Test
  void contextLoadsWithMigratedSchemaValidatedByHibernate() {
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
    assertThat(context.getBeansOfType(MonitoringScheduler.class)).isEmpty();
    assertThat(context.getBeansOfType(MonitoringTargetProvider.class)).hasSize(1);
    assertThat(context.getBeansOfType(WeeklyScheduleSource.class)).isEmpty();
    assertThat(context.getBeansOfType(NotificationDeliveryGateway.class)).isEmpty();
    assertThat(context.getBeansOfType(NotificationOutboxDispatcher.class)).isEmpty();
    assertThat(context.getBeansOfType(TelegramMessageTransport.class)).isEmpty();
    assertThat(context.getBeansOfType(TelegramNotificationDeliveryGateway.class)).isEmpty();
    assertThat(context.getBeansOfType(TelegramNotificationDispatchScheduler.class)).isEmpty();
    assertThat(context.getBeansOfType(TelegramLongPollingRuntime.class)).isEmpty();
    assertThat(context.getBeansOfType(TelegramLongPollingSupervisor.class)).isEmpty();
    assertThat(context.getBeansOfType(VulcanBrowserAuthenticator.class)).isEmpty();
    assertThat(context.getBeansOfType(VulcanConnectionService.class)).isEmpty();
    assertThat(context.getBeansOfType(VulcanSessionManager.class)).isEmpty();
  }
}
