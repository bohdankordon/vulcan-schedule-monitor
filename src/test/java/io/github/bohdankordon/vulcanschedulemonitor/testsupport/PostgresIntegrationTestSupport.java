package io.github.bohdankordon.vulcanschedulemonitor.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresIntegrationTestSupport {

  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.6")
          .withDatabaseName("schedule_monitor_test")
          .withUsername("schedule_monitor")
          .withPassword("synthetic-test-password");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
