package io.github.bohdankordon.vulcanschedulemonitor.vulcan;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap.SchoolBootstrap;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap.VulcanBootstrapAdapter;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.VulcanJournalAdapter;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule.VulcanScheduleAdapter;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VulcanSessionSnapshotScopingTest {

  private static final String APP_PATH = "/synthetic-app/";
  private static final Clock CLOCK = Clock.systemUTC();

  private WireMockServer server;
  private VulcanSession realSession;
  private VulcanSession sessionSpy;
  private VulcanHttpTransport transport;

  @BeforeEach
  void setUp() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    URI baseUri = URI.create(server.baseUrl() + APP_PATH);
    realSession =
        VulcanSession.fromBrowserSession(baseUri, "token", "app-guid", "c1=v1; c2=v2", baseUri);
    sessionSpy = spy(realSession);
    transport = new VulcanHttpTransport(sessionSpy, Duration.ofSeconds(2), Duration.ofSeconds(2));
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void getCacheSuccessAndFailureDoNotInvokeSnapshotMaterial() {
    VulcanBootstrapAdapter bootstrapAdapter =
        new VulcanBootstrapAdapter(sessionSpy, transport, CLOCK);

    // 1. Success
    server.stubFor(
        get(urlPathEqualTo(APP_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(text("get-cache"))));

    SchoolBootstrap cache = bootstrapAdapter.getCache();
    assertThat(cache).isNotNull();
    verify(sessionSpy, times(0)).snapshotMaterial();

    // 2. Failure (429)
    server.stubFor(
        get(urlPathEqualTo(APP_PATH + "DziennikCache.mvc/GetCache"))
            .willReturn(
                aResponse().withStatus(429).withHeader("Content-Type", "application/json")));

    clearInvocations(sessionSpy);
    assertThatThrownBy(bootstrapAdapter::getCache).isInstanceOf(VulcanHttpException.class);
    verify(sessionSpy, times(0)).snapshotMaterial();
  }

  @Test
  void getTreeSuccessAndFailureDoNotInvokeSnapshotMaterial() {
    VulcanJournalAdapter journalAdapter = new VulcanJournalAdapter(sessionSpy, transport, CLOCK);

    // 1. Success
    server.stubFor(
        get(urlPathEqualTo(APP_PATH + "Dziennik.mvc/GetTree"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(text("get-tree"))));

    List<SchoolClass> tree = journalAdapter.getTree(2026);
    assertThat(tree).isNotNull();
    verify(sessionSpy, times(0)).snapshotMaterial();

    // 2. Failure (429)
    server.stubFor(
        get(urlPathEqualTo(APP_PATH + "Dziennik.mvc/GetTree"))
            .willReturn(
                aResponse().withStatus(429).withHeader("Content-Type", "application/json")));

    clearInvocations(sessionSpy);
    assertThatThrownBy(() -> journalAdapter.getTree(2026)).isInstanceOf(VulcanHttpException.class);
    verify(sessionSpy, times(0)).snapshotMaterial();
  }

  @Test
  void schedule429InvokesSnapshotMaterialBeforeAndAfter() {
    VulcanScheduleAdapter scheduleAdapter = new VulcanScheduleAdapter(sessionSpy, transport);

    server.stubFor(
        post(urlPathEqualTo(APP_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"rate_limit\"}")));

    assertThatThrownBy(() -> scheduleAdapter.getWeekSchedule(1L, LocalDate.of(2026, 9, 7)))
        .isInstanceOf(VulcanHttpException.class);

    // Snapshot taken before request + after 429 response = exactly 2 times
    verify(sessionSpy, times(2)).snapshotMaterial();
  }

  @Test
  void scheduleSuccessInvokesSnapshotMaterialBeforeOnlyAndSucceeds() {
    VulcanScheduleAdapter scheduleAdapter = new VulcanScheduleAdapter(sessionSpy, transport);

    server.stubFor(
        post(urlPathEqualTo(APP_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(text("schedule-normal"))));

    ScheduleSnapshot snapshot = scheduleAdapter.getWeekSchedule(1L, LocalDate.of(2026, 9, 7));
    assertThat(snapshot).isNotNull();

    // Snapshot taken before request only on 2xx = exactly 1 time
    verify(sessionSpy, times(1)).snapshotMaterial();
  }
}
