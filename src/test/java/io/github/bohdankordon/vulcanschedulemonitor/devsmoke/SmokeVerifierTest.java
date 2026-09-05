package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.text;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.Stage;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Uses the real verifier/client/adapters/HTTP transport against loopback synthetic responses only.
 */
class SmokeVerifierTest {
  private static final String CACHE = "/synthetic/DziennikCache.mvc/GetCache";
  private static final String TREE = "/synthetic/Dziennik.mvc/GetTree";
  private WireMockServer server;
  private VulcanSessionMaterial material;
  private SmokeDiagnostics diagnostics;

  @BeforeEach
  void start() {
    server =
        new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
    server.start();
    URI uri = URI.create(server.baseUrl() + "/synthetic/");
    material =
        new VulcanSessionMaterial(
            uri, uri, "private-token", "private-guid", "session=private-cookie");
    diagnostics = new SmokeDiagnostics();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  @Test
  void realProductionPipelineReportsBothRequestsParsersYearAndSnapshot() {
    json(CACHE, text("get-cache"));
    json(TREE, text("get-tree"));
    var verified = new DefaultVulcanSessionVerifier(diagnostics).verifyAndDiscover(material);
    diagnostics.success(verified.classes().size());
    String report = report();
    for (Stage stage :
        new Stage[] {
          Stage.SESSION_MATERIAL_RECONSTRUCTION,
          Stage.VERIFY_CACHE_REQUEST,
          Stage.VERIFY_CACHE_PARSE,
          Stage.VERIFY_SCHOOL_YEAR,
          Stage.VERIFY_TREE_REQUEST,
          Stage.VERIFY_TREE_PARSE,
          Stage.SESSION_SNAPSHOT,
          Stage.VERIFIED
        }) {
      assertThat(report).contains("stage." + stage + "=PASS");
    }
    assertThat(report)
        .contains(
            "classCount=1", "category=SUCCESS", "http.VERIFY_CACHE_REQUEST=SUCCESS,JSON,false");
    server.verify(1, getRequestedFor(urlPathEqualTo(CACHE)));
    server.verify(1, getRequestedFor(urlPathEqualTo(TREE)));
  }

  @Test
  void sessionReconstructionFailureIsSeparateAndMakesNoRequest() {
    URI uri = material.applicationBaseUri();
    material =
        new VulcanSessionMaterial(uri, uri, "private-token", "private-guid", "invalid cookie");
    failure(Stage.SESSION_MATERIAL_RECONSTRUCTION, "PROTOCOL_FAILURE");
    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({
    "401,SESSION_AUTHENTICATION",
    "403,SESSION_AUTHENTICATION",
    "302,SESSION_AUTHENTICATION",
    "429,TRANSIENT",
    "503,TRANSIENT",
    "400,PROTOCOL_FAILURE"
  })
  void cacheHttpFailureStaysAtRequestAndNeverBecomesAParserFailure(int status, String category) {
    server.stubFor(
        get(urlPathEqualTo(CACHE))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Location", "https://private.example/destination")
                    .withHeader("Content-Type", "text/html")
                    .withBody("private response body")));
    failure(Stage.VERIFY_CACHE_REQUEST, category);
    assertThat(report()).contains("stage.VERIFY_CACHE_PARSE=NOT_REACHED");
    assertThat(server.getAllServeEvents()).hasSize(1);
  }

  @Test
  void authenticationStatusIsNotMaskedByAMalformedContentTypeHeader() {
    server.stubFor(
        get(urlPathEqualTo(CACHE))
            .willReturn(
                aResponse()
                    .withStatus(401)
                    .withHeader("Content-Type", "invalid content type")
                    .withBody("private body")));
    failure(Stage.VERIFY_CACHE_REQUEST, "SESSION_AUTHENTICATION");
    assertThat(report()).contains("http.VERIFY_CACHE_REQUEST=CLIENT_ERROR,OTHER,false");
  }

  @Test
  void loginHtmlIsASessionIssueEvenWithHttpSuccess() {
    server.stubFor(
        get(urlPathEqualTo(CACHE))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html>private login page</html>")));
    failure(Stage.VERIFY_CACHE_REQUEST, "SESSION_AUTHENTICATION");
    assertThat(report())
        .contains("httpFailure=UNEXPECTED_HTML", "http.VERIFY_CACHE_REQUEST=SUCCESS,HTML,false");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not-json-private-body",
        "{}",
        "{\"success\":true,\"data\":{\"currentSchoolYear\":2099}}"
      })
  void cacheJsonOrSchemaFailureIsLocalizedWithoutPayload(String response) {
    json(CACHE, response);
    failure(Stage.VERIFY_CACHE_PARSE, "PROTOCOL_FAILURE");
    assertThat(report()).contains("stage.VERIFY_CACHE_REQUEST=PASS");
  }

  @Test
  void currentSchoolYearExtractionHasItsOwnStage() {
    json(CACHE, "{\"success\":true,\"data\":{\"currentSchoolYear\":\"private-value\"}}");
    failure(Stage.VERIFY_SCHOOL_YEAR, "PROTOCOL_FAILURE");
    assertThat(report()).contains("stage.VERIFY_TREE_REQUEST=NOT_REACHED");
  }

  @Test
  void treeSessionFailureIsNotMisreportedAsTreeParsing() {
    json(CACHE, text("get-cache"));
    server.stubFor(
        get(urlPathEqualTo(TREE)).willReturn(aResponse().withStatus(403).withBody("private body")));
    failure(Stage.VERIFY_TREE_REQUEST, "SESSION_AUTHENTICATION");
    assertThat(report())
        .contains("stage.VERIFY_CACHE_PARSE=PASS", "stage.VERIFY_TREE_PARSE=NOT_REACHED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"not-json-private-body", "[]", "{\"ObjectData\":{\"IstniejeDziennik\":true}}"})
  void treeJsonAndSchemaErrorsAreLocalized(String response) {
    json(CACHE, text("get-cache"));
    json(TREE, response);
    failure(Stage.VERIFY_TREE_PARSE, "PROTOCOL_FAILURE");
    assertThat(report()).contains("stage.VERIFY_TREE_REQUEST=PASS");
  }

  private void json(String endpoint, String body) {
    server.stubFor(
        get(urlPathEqualTo(endpoint))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));
  }

  private void failure(Stage stage, String category) {
    assertThatThrownBy(
            () -> new DefaultVulcanSessionVerifier(diagnostics).verifyAndDiscover(material))
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            exception -> diagnostics.failed(exception.category()));
    assertThat(report())
        .contains("stage." + stage + "=FAIL", "category=" + category, "result=FAIL");
  }

  private String report() {
    String report = VulcanRealSmokeTest.report(diagnostics);
    assertThat(report)
        .doesNotContain(
            server.baseUrl(),
            "/synthetic/",
            "private",
            "session=",
            "<html>",
            "ObjectData",
            "currentSchoolYear");
    return report;
  }
}
