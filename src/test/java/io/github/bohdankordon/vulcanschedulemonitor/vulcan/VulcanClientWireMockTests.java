package io.github.bohdankordon.vulcanschedulemonitor.vulcan;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap.SchoolBootstrap;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VulcanClientWireMockTests {

  private static final String APPLICATION_PATH = "/synthetic-app/";
  private static final String TOKEN = "synthetic-token";
  private static final String APP_ID = "synthetic-app-id";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2099-09-09T10:15:30Z"), ZoneId.of("Europe/Warsaw"));

  private WireMockServer server;
  private VulcanClient client;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    URI applicationUri = URI.create(server.baseUrl() + APPLICATION_PATH);
    VulcanSession session =
        VulcanSession.fromBrowserSession(
            applicationUri, TOKEN, APP_ID, "seed=value=with=equals", applicationUri);
    client = new VulcanClient(session, CLOCK);
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void getCacheUsesBrowserHeadersAndMapsResponse() {
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(jsonResponse("get-cache")));

    SchoolBootstrap bootstrap = client.getCache();

    assertThat(bootstrap.currentSchoolYear()).isEqualTo(2099);
    assertThat(bootstrap.lessonPeriods()).hasSize(2);
    server.verify(
        getRequestedFor(urlPathEqualTo(APPLICATION_PATH + "DziennikCache.mvc/GetCache"))
            .withQueryParam("_dc", equalTo(Long.toString(CLOCK.millis())))
            .withHeader("X-V-RequestVerificationToken", equalTo(TOKEN))
            .withHeader("X-V-AppGuid", equalTo(APP_ID))
            .withHeader("X-Requested-With", equalTo("XMLHttpRequest"))
            .withHeader("Referer", equalTo(server.baseUrl() + APPLICATION_PATH)));
  }

  @Test
  void getTreeSendsCurrentSchoolYearAndRecursivelyDiscoversJournal() {
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "Dziennik.mvc/GetTree"))
            .willReturn(jsonResponse("get-tree")));

    List<SchoolClass> classes = client.getTree(2099);

    assertThat(classes).singleElement().extracting(SchoolClass::journalId).isEqualTo(4201L);
    server.verify(
        getRequestedFor(urlPathEqualTo(APPLICATION_PATH + "Dziennik.mvc/GetTree"))
            .withQueryParam("_dc", equalTo(Long.toString(CLOCK.millis())))
            .withQueryParam("zadanaData", equalTo("2099-09-09T00:00:00"))
            .withQueryParam("rokSzkolny", equalTo("2099"))
            .withQueryParam("idDziennik", equalTo(""))
            .withQueryParam("node", equalTo("root")));
  }

  @Test
  void weeklyScheduleUsesObservedFormAndReturnsExtractedChange() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(jsonResponse("schedule-substitution")));

    ScheduleSnapshot snapshot = client.getWeekSchedule(4201, LocalDate.of(2099, 9, 9));

    assertThat(snapshot.weekStart()).isEqualTo(LocalDate.of(2099, 9, 7));
    assertThat(snapshot.weekEnd()).isEqualTo(LocalDate.of(2099, 9, 13));
    assertThat(snapshot.changes()).singleElement().isInstanceOf(TeacherSubstitution.class);
    server.verify(
        postRequestedFor(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
            .withHeader("Origin", equalTo(server.baseUrl()))
            .withHeader("X-Requested-With", equalTo("XMLHttpRequest"))
            .withRequestBody(containing("dataOd=2099-09-07T00%3A00%3A00"))
            .withRequestBody(containing("dataDo=2099-09-13T00%3A00%3A00"))
            .withRequestBody(containing("idDziennik=4201"))
            .withRequestBody(containing("data=2099-09-09T00%3A00%3A00")));
  }

  @Test
  void cookieJarPreservesEqualsAndAppliesServerUpdatesToLaterRequests() {
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(
                jsonResponse("get-cache")
                    .withHeader("Set-Cookie", "rotated=second; Path=" + APPLICATION_PATH)));
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "Dziennik.mvc/GetTree"))
            .willReturn(jsonResponse("get-tree")));

    client.getCache();
    client.getTree(2099);

    server.verify(
        getRequestedFor(urlPathEqualTo(APPLICATION_PATH + "Dziennik.mvc/GetTree"))
            .withHeader("Cookie", containing("seed=value=with=equals"))
            .withHeader("Cookie", containing("rotated=second")));
  }

  @Test
  void httpFailureDoesNotExposeResponseBodyOrSessionMaterial() {
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(aResponse().withStatus(401).withBody("sensitive synthetic body")));

    assertThatThrownBy(client::getCache)
        .isInstanceOf(VulcanHttpException.class)
        .hasMessageContaining("GetCache")
        .hasMessageContaining("401")
        .hasMessageNotContaining("sensitive synthetic body")
        .hasMessageNotContaining(TOKEN)
        .hasMessageNotContaining(APP_ID);
  }

  @Test
  void invalidJsonFailsWithoutExposingResponseBody() {
    server.stubFor(
        get(urlPathEqualTo(APPLICATION_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("invalid synthetic response body")));

    assertThatThrownBy(client::getCache)
        .isInstanceOf(VulcanProtocolException.class)
        .hasMessageContaining("GetCache")
        .hasMessageNotContaining("invalid synthetic response body")
        .hasMessageNotContaining(TOKEN)
        .hasMessageNotContaining(APP_ID);
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(
      String fixture) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(text(fixture));
  }
}
