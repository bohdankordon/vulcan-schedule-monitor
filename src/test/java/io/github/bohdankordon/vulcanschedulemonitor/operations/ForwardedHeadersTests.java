package io.github.bohdankordon.vulcanschedulemonitor.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.testsupport.PostgresIntegrationTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "server.forward-headers-strategy=framework")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ForwardedHeadersTests extends PostgresIntegrationTestSupport {
  @LocalServerPort private int port;

  @Test
  void bootTreatsTrustedProxyHttpsAsSecureAndDirectHttpAsInsecure() throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var uri = URI.create("http://localhost:" + port + "/actuator/health/liveness");
      var direct =
          client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
      assertThat(direct.statusCode()).isEqualTo(200);
      assertThat(direct.headers().firstValue("Strict-Transport-Security")).isEmpty();
      var forwarded =
          client.send(
              HttpRequest.newBuilder(uri)
                  .header("X-Forwarded-Proto", "https")
                  .header("X-Forwarded-Host", "localhost:8443")
                  .header("X-Forwarded-For", "192.0.2.10")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(forwarded.statusCode()).isEqualTo(200);
      assertThat(forwarded.headers().firstValue("Strict-Transport-Security"))
          .hasValueSatisfying(value -> assertThat(value).contains("max-age="));
      assertThat(forwarded.body()).isEqualTo("{\"status\":\"UP\"}");
    }
  }
}
