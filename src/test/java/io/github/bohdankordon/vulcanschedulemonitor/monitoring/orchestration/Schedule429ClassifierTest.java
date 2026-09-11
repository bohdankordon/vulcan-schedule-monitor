package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.ContentFamily;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitHeaderPresence;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitResponseObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitedOperation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RequestShapeObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RequestShapeObservation.RequestContentTypeShape;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RequestShapeObservation.RequestMethodShape;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RetryAfterParseResult;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.SetCookieCount;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionCookieCountBucket;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionCookieMutationObservation;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class Schedule429ClassifierTest {

  @Test
  void nullObservationClassifiesAsRateLimited() {
    assertThat(Schedule429Classifier.classify(null)).isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  @Test
  void strictProductionSignatureClassifiesAsStaleSession() {
    RateLimitResponseObservation observation = strictStaleObservation();
    assertThat(Schedule429Classifier.classify(observation))
        .isEqualTo(Schedule429Disposition.STALE_SESSION);
  }

  @Test
  void cookieMutationsAndCountsDoNotAffectStaleClassification() {
    for (SetCookieCount count : SetCookieCount.values()) {
      for (boolean changed : List.of(false, true)) {
        SessionCookieMutationObservation mutation =
            new SessionCookieMutationObservation(
                SessionCookieCountBucket.SIX,
                SessionCookieCountBucket.SEVEN,
                changed,
                changed ? 1 : 0,
                0,
                0);
        RateLimitResponseObservation observation =
            new RateLimitResponseObservation(
                RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
                429,
                ContentFamily.HTML,
                RetryAfterParseResult.absent(),
                count,
                noRateLimitHeaders(),
                strictRequestShape(),
                mutation);
        assertThat(Schedule429Classifier.classify(observation))
            .isEqualTo(Schedule429Disposition.STALE_SESSION);
      }
    }
  }

  @Test
  void differentOperationClassifiesAsRateLimited() {
    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            RateLimitedOperation.OTHER,
            429,
            ContentFamily.HTML,
            RetryAfterParseResult.absent(),
            SetCookieCount.ONE,
            noRateLimitHeaders(),
            strictRequestShape(),
            emptyCookieMutation());
    assertThat(Schedule429Classifier.classify(observation))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  @ParameterizedTest
  @EnumSource(
      value = ContentFamily.class,
      names = {"JSON", "OTHER"})
  void nonHtmlContentFamilyClassifiesAsRateLimited(ContentFamily contentFamily) {
    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
            429,
            contentFamily,
            RetryAfterParseResult.absent(),
            SetCookieCount.ONE,
            noRateLimitHeaders(),
            strictRequestShape(),
            emptyCookieMutation());
    assertThat(Schedule429Classifier.classify(observation))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  @Test
  void deltaSecondsRetryAfterClassifiesAsRateLimited() {
    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
            429,
            ContentFamily.HTML,
            RetryAfterParseResult.deltaSeconds(Duration.ofSeconds(30)),
            SetCookieCount.ONE,
            noRateLimitHeaders(),
            strictRequestShape(),
            emptyCookieMutation());
    assertThat(Schedule429Classifier.classify(observation))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  @Test
  void httpDateRetryAfterClassifiesAsRateLimited() {
    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
            429,
            ContentFamily.HTML,
            RetryAfterParseResult.httpDate(Duration.ofSeconds(60)),
            SetCookieCount.ONE,
            noRateLimitHeaders(),
            strictRequestShape(),
            emptyCookieMutation());
    assertThat(Schedule429Classifier.classify(observation))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  @Test
  void malformedRetryAfterClassifiesAsRateLimited() {
    RateLimitResponseObservation observation =
        new RateLimitResponseObservation(
            RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
            429,
            ContentFamily.HTML,
            RetryAfterParseResult.malformed(),
            SetCookieCount.ONE,
            noRateLimitHeaders(),
            strictRequestShape(),
            emptyCookieMutation());
    assertThat(Schedule429Classifier.classify(observation))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  @Test
  void presentRateLimitHeadersClassifyAsRateLimited() {
    List<RateLimitHeaderPresence> headerVariations =
        List.of(
            new RateLimitHeaderPresence(true, false, false, false, false, false),
            new RateLimitHeaderPresence(false, true, false, false, false, false),
            new RateLimitHeaderPresence(false, false, true, false, false, false),
            new RateLimitHeaderPresence(false, false, false, true, false, false),
            new RateLimitHeaderPresence(false, false, false, false, true, false),
            new RateLimitHeaderPresence(false, false, false, false, false, true));

    for (RateLimitHeaderPresence headers : headerVariations) {
      RateLimitResponseObservation observation =
          new RateLimitResponseObservation(
              RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
              429,
              ContentFamily.HTML,
              RetryAfterParseResult.absent(),
              SetCookieCount.ONE,
              headers,
              strictRequestShape(),
              emptyCookieMutation());
      assertThat(Schedule429Classifier.classify(observation))
          .isEqualTo(Schedule429Disposition.RATE_LIMITED);
    }
  }

  @Test
  void missingRequestShapeHeadersClassifyAsRateLimited() {
    // origin missing
    assertThat(
            Schedule429Classifier.classify(
                observationWithShape(
                    new RequestShapeObservation(
                        RequestMethodShape.POST,
                        RequestContentTypeShape.FORM_URLENCODED,
                        false,
                        true,
                        true,
                        true,
                        true))))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);

    // referer missing
    assertThat(
            Schedule429Classifier.classify(
                observationWithShape(
                    new RequestShapeObservation(
                        RequestMethodShape.POST,
                        RequestContentTypeShape.FORM_URLENCODED,
                        true,
                        false,
                        true,
                        true,
                        true))))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);

    // token missing
    assertThat(
            Schedule429Classifier.classify(
                observationWithShape(
                    new RequestShapeObservation(
                        RequestMethodShape.POST,
                        RequestContentTypeShape.FORM_URLENCODED,
                        true,
                        true,
                        false,
                        true,
                        true))))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);

    // appGuid missing
    assertThat(
            Schedule429Classifier.classify(
                observationWithShape(
                    new RequestShapeObservation(
                        RequestMethodShape.POST,
                        RequestContentTypeShape.FORM_URLENCODED,
                        true,
                        true,
                        true,
                        false,
                        true))))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);

    // xRequestedWith missing
    assertThat(
            Schedule429Classifier.classify(
                observationWithShape(
                    new RequestShapeObservation(
                        RequestMethodShape.POST,
                        RequestContentTypeShape.FORM_URLENCODED,
                        true,
                        true,
                        true,
                        true,
                        false))))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestMethodShape.class,
      names = {"GET", "OTHER"})
  void nonPostMethodClassifiesAsRateLimited(RequestMethodShape method) {
    RequestShapeObservation shape =
        new RequestShapeObservation(
            method, RequestContentTypeShape.FORM_URLENCODED, true, true, true, true, true);
    assertThat(Schedule429Classifier.classify(observationWithShape(shape)))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  @ParameterizedTest
  @EnumSource(
      value = RequestContentTypeShape.class,
      names = {"JSON", "OTHER", "NONE"})
  void nonFormUrlencodedContentTypeClassifiesAsRateLimited(RequestContentTypeShape contentType) {
    RequestShapeObservation shape =
        new RequestShapeObservation(
            RequestMethodShape.POST, contentType, true, true, true, true, true);
    assertThat(Schedule429Classifier.classify(observationWithShape(shape)))
        .isEqualTo(Schedule429Disposition.RATE_LIMITED);
  }

  private static RateLimitResponseObservation strictStaleObservation() {
    return new RateLimitResponseObservation(
        RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
        429,
        ContentFamily.HTML,
        RetryAfterParseResult.absent(),
        SetCookieCount.ONE,
        noRateLimitHeaders(),
        strictRequestShape(),
        emptyCookieMutation());
  }

  private static RateLimitResponseObservation observationWithShape(RequestShapeObservation shape) {
    return new RateLimitResponseObservation(
        RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT,
        429,
        ContentFamily.HTML,
        RetryAfterParseResult.absent(),
        SetCookieCount.ONE,
        noRateLimitHeaders(),
        shape,
        emptyCookieMutation());
  }

  private static RateLimitHeaderPresence noRateLimitHeaders() {
    return new RateLimitHeaderPresence(false, false, false, false, false, false);
  }

  private static RequestShapeObservation strictRequestShape() {
    return new RequestShapeObservation(
        RequestMethodShape.POST,
        RequestContentTypeShape.FORM_URLENCODED,
        true,
        true,
        true,
        true,
        true);
  }

  private static SessionCookieMutationObservation emptyCookieMutation() {
    return new SessionCookieMutationObservation(
        SessionCookieCountBucket.SIX, SessionCookieCountBucket.SIX, false, 0, 0, 0);
  }
}
