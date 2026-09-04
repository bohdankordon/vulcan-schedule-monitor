package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultVulcanSessionVerifierWireMockTest {

  private static final String APPLICATION_PATH = "/synthetic-app/";
  private WireMockServer server;
  private VulcanSessionMaterial captured;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    URI applicationUri = URI.create(server.baseUrl() + APPLICATION_PATH);
    captured =
        new VulcanSessionMaterial(
            applicationUri, applicationUri, "synthetic-token", "synthetic-guid", "session=old");
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void returnsFinalCookieSnapshotAfterCacheAndTreeRotations() {
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(
                jsonResponse("get-cache")
                    .withHeader("Set-Cookie", "session=rotated; Path=" + APPLICATION_PATH)));
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "Dziennik.mvc/GetTree"))
            .withHeader("Cookie", containing("session=rotated"))
            .willReturn(
                jsonResponse("get-tree")
                    .withHeader("Set-Cookie", "session=final; Path=" + APPLICATION_PATH)));

    VerifiedVulcanSession verified = new DefaultVulcanSessionVerifier().verifyAndDiscover(captured);

    server.verify(
        getRequestedFor(urlPathEqualTo(APPLICATION_PATH + "Dziennik.mvc/GetTree"))
            .withHeader("Cookie", containing("session=rotated")));
    assertThat(verified.classes()).hasSize(1);
    assertThatThrownBy(verified.classes()::clear).isInstanceOf(UnsupportedOperationException.class);
    assertThat(verified.sessionMaterial().cookieHeader())
        .contains("session=final")
        .doesNotContain("session=old", "session=rotated");
    assertThat(verified.toString())
        .doesNotContain("session=final", "synthetic-token", "synthetic-guid");
  }

  @Test
  void mapsServerFailureToTransientWithoutParsingMessages() {
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(aResponse().withStatus(503).withBody("synthetic private body")));

    assertThatThrownBy(() -> new DefaultVulcanSessionVerifier().verifyAndDiscover(captured))
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            exception ->
                assertThat(exception.category()).isEqualTo(VulcanAuthFailureCategory.TRANSIENT))
        .hasMessageNotContaining("synthetic private body");
  }

  @Test
  void mapsMalformedProtocolToProtocolFailureWithoutResponseBody() {
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("synthetic malformed body")));

    assertThatThrownBy(() -> new DefaultVulcanSessionVerifier().verifyAndDiscover(captured))
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            exception ->
                assertThat(exception.category())
                    .isEqualTo(VulcanAuthFailureCategory.PROTOCOL_FAILURE))
        .hasMessageNotContaining("synthetic malformed body");
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(
      String fixture) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(text(fixture));
  }
}
