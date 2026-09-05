package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.text;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.CacheFailure;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.Stage;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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

  @ParameterizedTest
  @ValueSource(strings = {"get-cache", "get-cache-legacy-space"})
  void realProductionPipelineReportsBothRequestsParsersYearAndSnapshot(String cacheFixture) {
    json(CACHE, text(cacheFixture));
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
            "classCount=1", "category=SUCCESS", "http.VERIFY_CACHE_REQUEST=SUCCESS,JSON,false")
        .doesNotContain("cacheFailure=");
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

  @ParameterizedTest
  @EnumSource(CacheFailure.class)
  void periodFailuresReportOnlyTheFixedClassificationAndDoNotRequestTree(CacheFailure expected) {
    var mapper = new tools.jackson.databind.ObjectMapper();
    var period = mapper.createObjectNode();
    period.put("Id", 501);
    period.put("Numer", 1);
    period.put("Poczatek", "2000-01-01T08:00:00");
    period.put("Koniec", "2000-01-01T08:45:00");
    var periods = mapper.createArrayNode().add(period);
    var data = mapper.createObjectNode().put("currentSchoolYear", 2099);
    data.set("poryLekcji", periods);
    switch (expected) {
      case PERIODS_SCHEMA -> data.put("poryLekcji", "private schema value");
      case PERIOD_ID_SCHEMA -> period.put("Id", "private ID value");
      case PERIOD_NUMBER_SCHEMA -> period.put("Numer", "private number value");
      case PERIOD_START_SCHEMA -> period.remove("Poczatek");
      case PERIOD_END_SCHEMA -> period.remove("Koniec");
      case PERIOD_START_TIME_FORMAT -> period.put("Poczatek", "https://private.example/secret");
      case PERIOD_END_TIME_FORMAT -> period.put("Koniec", "private time value");
      case PERIOD_START_TIME_ONLY -> period.put("Poczatek", "08:00:00");
      case PERIOD_END_TIME_ONLY -> period.put("Koniec", "08:45:00");
      case PERIOD_NUMBER_RANGE -> period.put("Numer", -1);
      case DUPLICATE_PERIOD_ID -> periods.add(period.deepCopy());
    }
    var response = mapper.createObjectNode().put("success", true);
    response.set("data", data);
    json(CACHE, response.toString());

    failure(Stage.VERIFY_CACHE_PARSE, "PROTOCOL_FAILURE");

    assertThat(report())
        .contains("cacheFailure=" + expected, "stage.VERIFY_SCHOOL_YEAR=PASS")
        .doesNotContain("501", "2099", "08:00:00", "08:45:00");
    server.verify(1, getRequestedFor(urlPathEqualTo(CACHE)));
    server.verify(0, getRequestedFor(urlPathEqualTo(TREE)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1900-01-01X08:00:00",
        "prefix 1900-01-01 08:00:00",
        "1900-01-01 25:00:00",
        "1900-01-01T25:00:00",
        "1900-02-29 08:00:00",
        "1900-02-29T08:00:00",
        "1900-13-01 08:00:00",
        "malformedT08:00:00",
        "1900-01-01 08:00:00.123",
        "1900-01-01T08:00:00.123",
        "1900-01-01T08:00:00Z",
        "1900-01-01T08:00:00+01:00",
        "1900-01-01 08:00",
        "1900-01-01\t08:00:00",
        "1900-01-01  08:00:00",
        "1900-1-01 08:00:00"
      })
  void bothPeriodTimestampsRequireOneCompleteStrictSupportedShape(String invalid) {
    rejectPeriodFieldValues(
        invalid, CacheFailure.PERIOD_START_TIME_FORMAT, CacheFailure.PERIOD_END_TIME_FORMAT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\t"})
  void blankPeriodTimesRemainSchemaFailures(String blank) {
    rejectPeriodFieldValues(
        blank, CacheFailure.PERIOD_START_SCHEMA, CacheFailure.PERIOD_END_SCHEMA);
  }

  private void rejectPeriodFieldValues(
      String value, CacheFailure startFailure, CacheFailure endFailure) {
    for (String field : new String[] {"Poczatek", "Koniec"}) {
      var response =
          io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.json(
              "get-cache-legacy-space");
      var period =
          (tools.jackson.databind.node.ObjectNode) response.path("data").path("poryLekcji").get(0);
      period.put(field, value);
      json(CACHE, response.toString());
      diagnostics = new SmokeDiagnostics();

      failure(Stage.VERIFY_CACHE_PARSE, "PROTOCOL_FAILURE");

      assertThat(report())
          .contains("cacheFailure=" + (field.equals("Poczatek") ? startFailure : endFailure))
          .doesNotContain("1900-", "malformed", "prefix", "08:00");
    }
    server.verify(0, getRequestedFor(urlPathEqualTo(TREE)));
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
