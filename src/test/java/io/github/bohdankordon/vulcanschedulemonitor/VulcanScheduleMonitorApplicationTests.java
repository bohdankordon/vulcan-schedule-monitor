package io.github.bohdankordon.vulcanschedulemonitor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringScheduler;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTargetProvider;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.WeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationDeliveryGateway;
import io.github.bohdankordon.vulcanschedulemonitor.notification.delivery.NotificationOutboxDispatcher;
import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
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
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
    assertThat(context.getBeansOfType(MonitoringScheduler.class)).isEmpty();
    assertThat(context.getBeansOfType(MonitoringTargetProvider.class)).hasSize(1);
    assertThat(context.getBeansOfType(WeeklyScheduleSource.class)).isEmpty();
    assertThat(context.getBeansOfType(NotificationDeliveryGateway.class)).isEmpty();
    assertThat(context.getBeansOfType(NotificationOutboxDispatcher.class)).isEmpty();
  }
}
