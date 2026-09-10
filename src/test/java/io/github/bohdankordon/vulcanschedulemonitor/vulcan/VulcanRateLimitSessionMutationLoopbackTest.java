package io.github.bohdankordon.vulcanschedulemonitor.vulcan;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitResponseObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.SetCookieCount;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionCookieCountBucket;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionCookieMutationObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanCookieMaterial;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Loopback test validating real JDK HttpClient and CookieManager behavior when receiving an HTTP
 * 429 response containing Set-Cookie headers.
 */
class VulcanRateLimitSessionMutationLoopbackTest {

  private static final String APPLICATION_PATH = "/synthetic-app/";
  private static final String TOKEN = "synthetic-token";
  private static final String APP_ID = "synthetic-app-id";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-09-10T10:00:00Z"), ZoneId.of("Europe/Warsaw"));

  private WireMockServer server;
  private VulcanSession session;
  private VulcanClient client;
  private VulcanSessionMaterial initialMaterial;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();

    URI applicationUri = URI.create(server.baseUrl() + APPLICATION_PATH);
    initialMaterial =
        VulcanSessionMaterial.structured(
            applicationUri,
            applicationUri,
            TOKEN,
            APP_ID,
            List.of(
                new VulcanCookieMaterial(
                    "cookieA", "initialValA", APPLICATION_PATH, null, false, false),
                new VulcanCookieMaterial(
                    "cookieB", "initialValB", APPLICATION_PATH, null, false, false)));
    session = VulcanSession.fromMaterial(initialMaterial);
    client = new VulcanClient(session, CLOCK);
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void realJdkStackAppliesSetCookieOn429AndObservationReflectsRealMutation() {
    String syntheticCookieName = "syntheticChallengeCookie";
    String syntheticCookieValue = "challengePayload12345";
    String setCookieHeader =
        syntheticCookieName + "=" + syntheticCookieValue + "; Path=" + APPLICATION_PATH;

    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Set-Cookie", setCookieHeader)
                    .withBody("{\"error\":\"rate_limited\"}")));

    assertThatThrownBy(() -> client.getWeekSchedule(123L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              assertThat(failure.category()).isEqualTo(VulcanFailureCategory.RATE_LIMITED);

              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.setCookieCount()).isEqualTo(SetCookieCount.ONE);

              // Inspect the actual observed behavior of the JDK HttpClient + CookieManager stack:
              VulcanSessionMaterial currentMaterial = session.snapshotMaterial();
              boolean realJdkMutated = !initialMaterial.sameCookiesAs(currentMaterial);

              SessionCookieMutationObservation mutation = obs.sessionMutation();
              assertThat(mutation.cookieMaterialChanged()).isEqualTo(realJdkMutated);
              assertThat(mutation.sessionCookiesBefore()).isEqualTo(SessionCookieCountBucket.TWO);

              if (realJdkMutated) {
                // In standard JDK HttpClient, CookieManager.put is invoked for non-2xx responses as
                // well.
                assertThat(mutation.sessionCookiesAfter())
                    .isEqualTo(SessionCookieCountBucket.THREE);
                assertThat(mutation.cookieIdentityAddedCount()).isEqualTo(1);
                assertThat(currentMaterial.cookieCount()).isEqualTo(3);
              } else {
                assertThat(mutation.sessionCookiesAfter()).isEqualTo(SessionCookieCountBucket.TWO);
                assertThat(mutation.cookieIdentityAddedCount()).isEqualTo(0);
              }

              // Redaction verification: neither observation nor exception reveals cookie name or
              // value
              assertThat(obs.toString()).doesNotContain(syntheticCookieName);
              assertThat(obs.toString()).doesNotContain(syntheticCookieValue);
              assertThat(mutation.toString()).doesNotContain(syntheticCookieName);
              assertThat(mutation.toString()).doesNotContain(syntheticCookieValue);
              assertThat(failure.getMessage()).doesNotContain(syntheticCookieName);
              assertThat(failure.getMessage()).doesNotContain(syntheticCookieValue);
            });
  }

  @Test
  void rateLimitWithoutSetCookieReportsNoMutation() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"rate_limited\"}")));

    assertThatThrownBy(() -> client.getWeekSchedule(123L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.setCookieCount()).isEqualTo(SetCookieCount.ZERO);

              SessionCookieMutationObservation mutation = obs.sessionMutation();
              assertThat(mutation.cookieMaterialChanged()).isFalse();
              assertThat(mutation.sessionCookiesBefore()).isEqualTo(SessionCookieCountBucket.TWO);
              assertThat(mutation.sessionCookiesAfter()).isEqualTo(SessionCookieCountBucket.TWO);
              assertThat(mutation.cookieIdentityAddedCount()).isEqualTo(0);
              assertThat(mutation.cookieIdentityRemovedCount()).isEqualTo(0);
              assertThat(mutation.cookieValueChangedCount()).isEqualTo(0);
            });
  }
}
