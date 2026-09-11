package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.ContentFamily;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitResponseObservation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RateLimitedOperation;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RequestShapeObservation.RequestContentTypeShape;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RequestShapeObservation.RequestMethodShape;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.RetryAfterRepresentation;

/**
 * Classifies HTTP 429 schedule responses into either ordinary rate limiting or stale session
 * requiring authentication recovery.
 *
 * <p>A strict stale session signature requires all of:
 *
 * <ul>
 *   <li>Observation is present
 *   <li>Operation is {@code GetPlanLekcjiContext}
 *   <li>Status code is 429
 *   <li>Response content family is HTML
 *   <li>{@code Retry-After} representation is {@code ABSENT}
 *   <li>No {@code RateLimit-*} or {@code X-RateLimit-*} headers are present
 *   <li>Request method is POST
 *   <li>Request content type is {@code application/x-www-form-urlencoded}
 *   <li>Request headers Origin, Referer, verification token, AppGuid, and X-Requested-With are
 *       present
 * </ul>
 *
 * All other responses default safely to {@link Schedule429Disposition#RATE_LIMITED}.
 */
public final class Schedule429Classifier {

  private Schedule429Classifier() {}

  public static Schedule429Disposition classify(RateLimitResponseObservation observation) {
    if (observation == null) {
      return Schedule429Disposition.RATE_LIMITED;
    }
    if (observation.operation() != RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT) {
      return Schedule429Disposition.RATE_LIMITED;
    }
    if (observation.statusCode() != 429) {
      return Schedule429Disposition.RATE_LIMITED;
    }
    if (observation.contentFamily() != ContentFamily.HTML) {
      return Schedule429Disposition.RATE_LIMITED;
    }
    if (observation.retryAfterRepresentation() != RetryAfterRepresentation.ABSENT) {
      return Schedule429Disposition.RATE_LIMITED;
    }
    if (observation.rateLimitHeaders().anyPresent()) {
      return Schedule429Disposition.RATE_LIMITED;
    }
    var shape = observation.requestShape();
    if (shape.method() != RequestMethodShape.POST
        || shape.contentType() != RequestContentTypeShape.FORM_URLENCODED
        || !shape.originPresent()
        || !shape.refererPresent()
        || !shape.verificationTokenPresent()
        || !shape.appGuidPresent()
        || !shape.xRequestedWithPresent()) {
      return Schedule429Disposition.RATE_LIMITED;
    }
    return Schedule429Disposition.STALE_SESSION;
  }
}
