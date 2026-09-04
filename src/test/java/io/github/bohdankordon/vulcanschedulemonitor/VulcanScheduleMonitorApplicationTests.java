package io.github.bohdankordon.vulcanschedulemonitor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class VulcanScheduleMonitorApplicationTests extends PostgresIntegrationTestSupport {

  @Autowired private Flyway flyway;

  @Test
  void contextLoadsWithMigratedSchemaValidatedByHibernate() {
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
  }
}
