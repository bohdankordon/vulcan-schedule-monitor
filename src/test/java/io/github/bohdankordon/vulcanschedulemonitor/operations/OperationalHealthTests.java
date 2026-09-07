package io.github.bohdankordon.vulcanschedulemonitor.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OperationalHealthTests extends PostgresIntegrationTestSupport {

  private static final List<String> PROBES =
      List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

  @Autowired private MockMvc mvc;
  @Autowired private HealthContributorRegistry contributors;
  @Autowired private HealthEndpointGroups groups;
  @Autowired private WebEndpointsSupplier webEndpoints;
  @Autowired private ApplicationContext context;

  @ParameterizedTest
  @ValueSource(
      strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"})
  void anonymousGetIsHealthyAndHidesComponentsAndDetails(String path) throws Exception {
    assertThat(contributors.getContributor("db")).isInstanceOf(DataSourceHealthIndicator.class);
    assertProbe(path, 200, "UP");
  }

  @Test
  void onlyHealthIsExposedEvenBeforeSecurityIsApplied() {
    assertThat(webEndpoints.getEndpoints())
        .extracting(endpoint -> endpoint.getEndpointId().toString())
        .containsExactly("health");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/actuator",
        "/actuator/env",
        "/actuator/configprops",
        "/actuator/beans",
        "/actuator/mappings",
        "/actuator/threaddump",
        "/actuator/heapdump",
        "/actuator/loggers",
        "/actuator/metrics",
        "/actuator/scheduledtasks",
        "/actuator/flyway",
        "/actuator/conditions",
        "/actuator/caches",
        "/actuator/health/db",
        "/actuator/health/arbitrary-component",
        "/actuator/health/liveness/livenessState",
        "/actuator/health/readiness/db",
        "/actuator/health/",
        "/actuator/health/readiness/",
        "/",
        "/unrelated",
        "/livez",
        "/readyz",
        "/login",
        "/logout"
      })
  void otherPathsAreDeniedBySecurity(String path) throws Exception {
    mvc.perform(get(path)).andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"})
  void onlyGetIsPublicEvenWithValidCsrf(String method) throws Exception {
    for (String path : PROBES) {
      mvc.perform(request(HttpMethod.valueOf(method), path)).andExpect(status().isForbidden());
      mvc.perform(request(HttpMethod.valueOf(method), path).with(csrf()))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void groupsUseOnlyTheirIntendedContributors() {
    assertThat(groups.getNames()).containsExactlyInAnyOrder("liveness", "readiness");
    for (String name :
        List.of(
            "livenessState",
            "readinessState",
            "db",
            "diskSpace",
            "ssl",
            "ping",
            "telegram",
            "vulcan")) {
      assertThat(groups.get("liveness").isMember(name))
          .as("liveness includes %s", name)
          .isEqualTo(name.equals("livenessState"));
      assertThat(groups.get("readiness").isMember(name))
          .as("readiness includes %s", name)
          .isEqualTo(name.equals("readinessState") || name.equals("db"));
    }
  }

  @Test
  void databaseFailureMakesReadinessUnavailableButLeavesLivenessHealthy() throws Exception {
    // Keep the actual configured group and HTTP stack; never stop the shared PostgreSQL container.
    var original = contributors.unregisterContributor("db");
    var databaseHealth =
        new AtomicReference<>(
            Health.up().withDetail("database", "synthetic-private-vendor").build());
    contributors.registerContributor("db", (HealthIndicator) databaseHealth::get);
    try {
      for (String path : PROBES) {
        assertProbe(path, 200, "UP");
      }
      databaseHealth.set(
          Health.down()
              .withDetail("url", "jdbc:postgresql://synthetic-private-host/database")
              .withDetail("error", "synthetic-private-exception")
              .build());
      assertProbe("/actuator/health/readiness", 503, "DOWN");
      assertProbe("/actuator/health", 503, "DOWN");
      assertProbe("/actuator/health/liveness", 200, "UP");
      databaseHealth.set(Health.up().build());
      assertProbe("/actuator/health/readiness", 200, "UP");
    } finally {
      contributors.unregisterContributor("db");
      contributors.registerContributor("db", original);
    }
  }

  @Test
  void refusingTrafficMakesReadinessUnavailableWithoutFailingLiveness() throws Exception {
    AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC);
    try {
      assertProbe("/actuator/health/readiness", 503, "OUT_OF_SERVICE");
      assertProbe("/actuator/health/liveness", 200, "UP");
    } finally {
      AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
    }
  }

  private void assertProbe(String path, int httpStatus, String healthStatus) throws Exception {
    // Boot includes public group names at the root even when details/components are hidden.
    String body =
        path.equals("/actuator/health")
            ? "{\"status\":\"%s\",\"groups\":[\"liveness\",\"readiness\"]}".formatted(healthStatus)
            : "{\"status\":\"%s\"}".formatted(healthStatus);
    mvc.perform(get(path))
        .andExpect(status().is(httpStatus))
        .andExpect(content().json(body, JsonCompareMode.STRICT));
  }
}
