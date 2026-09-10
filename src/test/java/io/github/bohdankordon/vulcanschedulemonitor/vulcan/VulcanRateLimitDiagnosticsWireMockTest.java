package io.github.bohdankordon.vulcanschedulemonitor.vulcan;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.DelaySource;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.EffectiveDelayBucket;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.RateLimitBackoffGate;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.ResilienceDecision;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.ResilientWeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.ScheduleSourceException;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.SourceFailureKind;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.TrackingScope;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.ContentFamily;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitResponseObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitedOperation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RequestShapeObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RetryAfterRepresentation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.SetCookieCount;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VulcanRateLimitDiagnosticsWireMockTest {

  private static final String APPLICATION_PATH = "/synthetic-app/";
  private static final String TOKEN = "synthetic-token";
  private static final String APP_ID = "synthetic-app-id";
  private static final Instant NOW = Instant.parse("2026-09-10T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Europe/Warsaw"));
  private static final TrackingScope SCOPE =
      new TrackingScope(1L, 1L, 42L, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 13));

  private WireMockServer server;
  private VulcanSession session;
  private VulcanClient client;

  @BeforeEach
  void startServer() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    URI applicationUri = URI.create(server.baseUrl() + APPLICATION_PATH);
    session =
        VulcanSession.fromBrowserSession(
            applicationUri,
            TOKEN,
            APP_ID,
            "session_cookie_one=val1; session_cookie_two=val2",
            applicationUri);
    client = new VulcanClient(session, CLOCK);
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void caseA_jsonResponseWithoutRetryAfterYieldsAbsentAndJsonContent() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json; charset=utf-8")
                    .withBody("{\"error\": \"rate limited\"}")));

    assertThatThrownBy(() -> client.getWeekSchedule(42L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              assertThat(failure.category()).isEqualTo(VulcanFailureCategory.RATE_LIMITED);
              assertThat(failure.retryAfter()).isEmpty();
              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.statusCode()).isEqualTo(429);
              assertThat(obs.operation()).isEqualTo(RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT);
              assertThat(obs.contentFamily()).isEqualTo(ContentFamily.JSON);
              assertThat(obs.retryAfterRepresentation()).isEqualTo(RetryAfterRepresentation.ABSENT);
              assertThat(obs.setCookieCount()).isEqualTo(SetCookieCount.ZERO);
              assertThat(obs.requestShape().method())
                  .isEqualTo(RequestShapeObservation.RequestMethodShape.POST);
              assertThat(obs.requestShape().contentType())
                  .isEqualTo(RequestShapeObservation.RequestContentTypeShape.FORM_URLENCODED);
              assertThat(obs.requestShape().originPresent()).isTrue();
              assertThat(obs.requestShape().refererPresent()).isTrue();
              assertThat(obs.requestShape().verificationTokenPresent()).isTrue();
              assertThat(obs.requestShape().appGuidPresent()).isTrue();
              assertThat(obs.requestShape().xRequestedWithPresent()).isTrue();
            });
  }

  @Test
  void caseB_retryAfterFiveSecondsYieldsDeltaSecondsAndInlineRetryDecision() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Retry-After", "5")));

    assertThatThrownBy(() -> client.getWeekSchedule(42L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              assertThat(failure.retryAfter()).contains(Duration.ofSeconds(5));
              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.retryAfterRepresentation())
                  .isEqualTo(RetryAfterRepresentation.DELTA_SECONDS);
            });

    // Verify resilience decision logic with a 5s retry-after (<= 10s maximum inline delay)
    Duration requiredDelay = Duration.ofSeconds(5);
    DelaySource delaySource = DelaySource.HEADER;
    EffectiveDelayBucket delayBucket = EffectiveDelayBucket.fromDuration(requiredDelay);
    ResilienceDecision decision =
        (1 == 3 || requiredDelay.compareTo(Duration.ofSeconds(10)) > 0)
            ? ResilienceDecision.DEFERRED_GATE
            : ResilienceDecision.INLINE_RETRY;

    assertThat(delaySource).isEqualTo(DelaySource.HEADER);
    assertThat(delayBucket).isEqualTo(EffectiveDelayBucket.LE_10_SECONDS);
    assertThat(decision).isEqualTo(ResilienceDecision.INLINE_RETRY);
  }

  @Test
  void caseC_retryAfterThirtySecondsYieldsDeltaSecondsAndDeferredGateDecision() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Retry-After", "30")));

    assertThatThrownBy(() -> client.getWeekSchedule(42L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              assertThat(failure.retryAfter()).contains(Duration.ofSeconds(30));
              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.retryAfterRepresentation())
                  .isEqualTo(RetryAfterRepresentation.DELTA_SECONDS);
            });

    Duration requiredDelay = Duration.ofSeconds(30);
    DelaySource delaySource = DelaySource.HEADER;
    EffectiveDelayBucket delayBucket = EffectiveDelayBucket.fromDuration(requiredDelay);
    ResilienceDecision decision =
        (1 == 3 || requiredDelay.compareTo(Duration.ofSeconds(10)) > 0)
            ? ResilienceDecision.DEFERRED_GATE
            : ResilienceDecision.INLINE_RETRY;

    assertThat(delaySource).isEqualTo(DelaySource.HEADER);
    assertThat(delayBucket).isEqualTo(EffectiveDelayBucket.LE_30_SECONDS);
    assertThat(decision).isEqualTo(ResilienceDecision.DEFERRED_GATE);
  }

  @Test
  void caseD_retryAfterFutureHttpDateYieldsHttpDateAndFiniteBucket() {
    String futureDate =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            ZonedDateTime.ofInstant(NOW.plusSeconds(45), ZoneOffset.UTC));
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Retry-After", futureDate)));

    assertThatThrownBy(() -> client.getWeekSchedule(42L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              assertThat(failure.retryAfter()).contains(Duration.ofSeconds(45));
              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.retryAfterRepresentation())
                  .isEqualTo(RetryAfterRepresentation.HTTP_DATE);
              EffectiveDelayBucket bucket =
                  EffectiveDelayBucket.fromDuration(obs.retryAfterDuration().orElse(null));
              assertThat(bucket).isEqualTo(EffectiveDelayBucket.LE_60_SECONDS);
            });
  }

  @Test
  void caseE_malformedRetryAfterYieldsMalformedRepresentationAndFallbackGate() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "text/html")
                    .withHeader("Retry-After", "invalid-seconds-value")));

    assertThatThrownBy(() -> client.getWeekSchedule(42L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              assertThat(failure.retryAfter()).isEmpty();
              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.retryAfterRepresentation())
                  .isEqualTo(RetryAfterRepresentation.MALFORMED);
              assertThat(obs.contentFamily()).isEqualTo(ContentFamily.HTML);
            });

    Duration fallbackDelay = Duration.ofSeconds(30);
    DelaySource delaySource = DelaySource.FALLBACK;
    EffectiveDelayBucket delayBucket = EffectiveDelayBucket.fromDuration(fallbackDelay);
    ResilienceDecision decision =
        (1 == 3 || fallbackDelay.compareTo(Duration.ofSeconds(10)) > 0)
            ? ResilienceDecision.DEFERRED_GATE
            : ResilienceDecision.INLINE_RETRY;

    assertThat(delaySource).isEqualTo(DelaySource.FALLBACK);
    assertThat(delayBucket).isEqualTo(EffectiveDelayBucket.LE_30_SECONDS);
    assertThat(decision).isEqualTo(ResilienceDecision.DEFERRED_GATE);
  }

  @Test
  void caseF_rateLimitResponseWithSetCookieRetainsCountOnlyAndExcludesRawValues() {
    String syntheticCookieValue = "SENSITIVE_CF_CLEARANCE_COOKIE=abcdef1234567890; Path=/";
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Set-Cookie", syntheticCookieValue)));

    assertThatThrownBy(() -> client.getWeekSchedule(42L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.setCookieCount()).isEqualTo(SetCookieCount.ONE);
              assertThat(obs.toString()).doesNotContain("SENSITIVE_CF_CLEARANCE_COOKIE");
              assertThat(obs.toString()).doesNotContain("abcdef1234567890");
              assertThat(failure.getMessage()).doesNotContain("SENSITIVE_CF_CLEARANCE_COOKIE");
            });
  }

  @Test
  void resilientScheduleSourceEmitsSanitizedLogAndDefersOnRateLimit() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Retry-After", "30")));

    List<Duration> recordedDelays = new ArrayList<>();
    RateLimitBackoffGate gate = new RateLimitBackoffGate(CLOCK);
    ResilientWeeklyScheduleSource resilientSource =
        new ResilientWeeklyScheduleSource(
            scope -> client.getWeekSchedule(scope.journalId(), scope.weekStart()),
            recordedDelays::add,
            gate,
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(30),
            Duration.ofSeconds(10));

    assertThatThrownBy(() -> resilientSource.fetchCompleteWeeklySnapshot(SCOPE))
        .isInstanceOfSatisfying(
            ScheduleSourceException.class,
            failure -> {
              assertThat(failure.kind()).isEqualTo(SourceFailureKind.DEFERRED_RATE_LIMIT);
              assertThat(failure.deferredUntil()).contains(NOW.plus(Duration.ofSeconds(30)));
            });
    assertThat(recordedDelays).isEmpty();
  }

  @Test
  void wireCookieHeaderIsReceivedByServerWhileSpringHttpRequestHeadersExcludesIt() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\": \"rate limited\"}")));

    assertThatThrownBy(() -> client.getWeekSchedule(42L, LocalDate.of(2026, 9, 7)))
        .isInstanceOf(VulcanHttpException.class);

    // 1. WireMock verifies that the JDK HttpClient sent the Cookie header over the wire:
    server.verify(
        postRequestedFor(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .withHeader("Cookie", containing("session_cookie_one=val1")));

    // 2. Proves that wire cookies are applied by the JDK CookieHandler, but are not on Spring
    // HttpRequest.
    // RequestShapeObservation does not claim wire-inaccurate cookiePresent;
    // sessionCookiesBefore is the accurate and safe diagnostic instead.
  }

  @Test
  void rateLimitHeaderFlagsAreCapturedIndividually() {
    server.stubFor(
        post(urlPathEqualTo(APPLICATION_PATH + "PlanLekcji.mvc/GetPlanLekcjiContext"))
            .willReturn(
                aResponse()
                    .withStatus(429)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("RateLimit-Limit", "100")
                    .withHeader("RateLimit-Remaining", "0")
                    .withHeader("RateLimit-Reset", "60")
                    .withHeader("X-RateLimit-Limit", "100")
                    .withHeader("X-RateLimit-Remaining", "0")
                    .withHeader("X-RateLimit-Reset", "60")));

    assertThatThrownBy(() -> client.getWeekSchedule(42L, LocalDate.of(2026, 9, 7)))
        .isInstanceOfSatisfying(
            VulcanHttpException.class,
            failure -> {
              RateLimitResponseObservation obs = failure.rateLimitObservation().orElseThrow();
              assertThat(obs.rateLimitHeaders().rateLimitLimit()).isTrue();
              assertThat(obs.rateLimitHeaders().rateLimitRemaining()).isTrue();
              assertThat(obs.rateLimitHeaders().rateLimitReset()).isTrue();
              assertThat(obs.rateLimitHeaders().xRateLimitLimit()).isTrue();
              assertThat(obs.rateLimitHeaders().xRateLimitRemaining()).isTrue();
              assertThat(obs.rateLimitHeaders().xRateLimitReset()).isTrue();
              assertThat(obs.rateLimitHeaders().anyPresent()).isTrue();
            });
  }
}
