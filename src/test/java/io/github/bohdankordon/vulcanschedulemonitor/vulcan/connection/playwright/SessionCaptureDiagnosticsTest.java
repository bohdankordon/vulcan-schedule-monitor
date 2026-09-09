package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SessionCaptureDiagnosticsTest {
  static final String REQUEST =
      "https://school.vulcan.net.pl/SECRET_TENANT_PATH/Dziennik.mvc/GetTree?SECRET_QUERY";
  static final String REFERER =
      "https://school.vulcan.net.pl/SECRET_TENANT_PATH/SUPER_SECRET_REFERER";
  static final String TOKEN = "SUPER_SECRET_TOKEN";
  static final String GUID = "SUPER_SECRET_APPGUID";
  static final String COOKIE_NAME = "SUPER_SECRET_COOKIE_NAME";
  static final String COOKIE_VALUE = "SUPER_SECRET_COOKIE";
  static final String DETAILS =
      REQUEST
          + " "
          + REFERER
          + " "
          + TOKEN
          + " "
          + GUID
          + " "
          + COOKIE_NAME
          + " "
          + COOKIE_VALUE
          + " private exception message";
  private final PortalUrlValidator urls = new PortalUrlValidator();
  private final PlaywrightVulcanBrowserAuthenticator authenticator =
      new PlaywrightVulcanBrowserAuthenticator(urls, true);
  private final SessionCaptureDiagnostics diagnostics = new SessionCaptureDiagnostics();
  private final List<BrowserRequestObservation> complete = new ArrayList<>();

  @Test
  void noAllowedRequestsHasItsOwnFailureAndNoCookieLookupFact() {
    failCapture(List.of());
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.NO_ALLOWED_REQUEST);
    assertThat(diagnostics.snapshot().allowedRequests())
        .isEqualTo(SessionCaptureObservation.AllowedRequestCount.ZERO);
    assertThat(diagnostics.snapshot().cookieCount())
        .isEqualTo(SessionCaptureObservation.CookieCount.UNAVAILABLE);
  }

  @Test
  void resetRestoresAllFieldsToInitialCleanState() {
    diagnostics.allowedRequest();
    diagnostics.referer(true);
    diagnostics.verificationToken(true);
    diagnostics.appGuid(true);
    diagnostics.completeRequest();
    diagnostics.candidate();
    diagnostics.candidateWithCookies();
    diagnostics.cookies(5);
    diagnostics.rejected(SessionCaptureFailureKind.REFERER_REJECTED);
    diagnostics.exhausted();

    diagnostics.reset();

    var snapshot = diagnostics.snapshot();
    assertThat(snapshot.failure()).isEqualTo(SessionCaptureFailureKind.NO_ALLOWED_REQUEST);
    assertThat(snapshot.allowedRequests())
        .isEqualTo(SessionCaptureObservation.AllowedRequestCount.ZERO);
    assertThat(snapshot.completeRequests())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.ZERO);
    assertThat(snapshot.sawReferer()).isFalse();
    assertThat(snapshot.sawVerificationToken()).isFalse();
    assertThat(snapshot.sawAppGuid()).isFalse();
    assertThat(snapshot.sawAllRequiredHeadersTogether()).isFalse();
    assertThat(snapshot.candidates())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.ZERO);
    assertThat(snapshot.candidatesWithCookies())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.ZERO);
    assertThat(snapshot.cookieCount()).isEqualTo(SessionCaptureObservation.CookieCount.UNAVAILABLE);
  }

  @ParameterizedTest
  @CsvSource({
    "false,false,false",
    "true,false,false",
    "false,true,false",
    "false,false,true",
    "true,true,false",
    "true,false,true",
    "false,true,true",
    "true,true,true"
  })
  void onlyPresenceIsRetainedAndOnlyCompleteRequestsEnterTheProductionList(
      boolean referer, boolean token, boolean guid) {
    observe(REQUEST, referer ? REFERER : " ", token ? TOKEN : null, guid ? GUID : "");
    var fact = diagnostics.snapshot();
    assertThat(fact.allowedRequests()).isEqualTo(SessionCaptureObservation.AllowedRequestCount.ONE);
    assertThat(fact.sawReferer()).isEqualTo(referer);
    assertThat(fact.sawVerificationToken()).isEqualTo(token);
    assertThat(fact.sawAppGuid()).isEqualTo(guid);
    assertThat(fact.sawAllRequiredHeadersTogether()).isEqualTo(referer && token && guid);
    assertThat(complete).hasSize(referer && token && guid ? 1 : 0);
    assertThat(fact.completeRequests())
        .isEqualTo(
            referer && token && guid
                ? SessionCaptureObservation.CompleteRequestCount.ONE
                : SessionCaptureObservation.CompleteRequestCount.ZERO);
    failCapture(List.of());
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(
            referer && token && guid
                ? SessionCaptureFailureKind.NO_MATCHING_COOKIES
                : SessionCaptureFailureKind.NO_COMPLETE_REQUEST);
  }

  @Test
  void distributedHeadersNeverBecomeACompleteCandidate() {
    observe(REQUEST, REFERER, null, null);
    observe(REQUEST, null, TOKEN, null);
    observe(REQUEST, null, null, GUID);
    failCapture(List.of());
    var fact = diagnostics.snapshot();
    assertThat(fact.allowedRequests())
        .isEqualTo(SessionCaptureObservation.AllowedRequestCount.TWO_TO_FIVE);
    assertThat(fact.sawReferer() && fact.sawVerificationToken() && fact.sawAppGuid()).isTrue();
    assertThat(fact.sawAllRequiredHeadersTogether()).isFalse();
    assertThat(fact.failure()).isEqualTo(SessionCaptureFailureKind.NO_COMPLETE_REQUEST);
    assertThat(complete).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://external.example/SECRET_QUERY",
        "http://school.vulcan.net.pl/",
        "https://school.vulcan.net.pl:8443/",
        "not a uri SECRET_TENANT_PATH"
      })
  void disallowedOrMalformedRequestsDoNotContributeFactsOrReadHeaders(String uri) {
    Request request = request(uri, REFERER, TOKEN, GUID);
    clearInvocations(request);
    authenticator.observeAuthenticatedRequest(request, complete, diagnostics);
    verify(request, never()).headerValue(anyString());
    failCapture(List.of());
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.NO_ALLOWED_REQUEST);
    assertThat(complete).isEmpty();
  }

  @Test
  void headerReadFailureRetainsOnlyFactsReadBeforeItAndNeverItsException() {
    Request request = request(REQUEST, REFERER, TOKEN, GUID);
    when(request.headerValue("x-v-requestverificationtoken"))
        .thenThrow(new PlaywrightException(DETAILS));
    authenticator.observeAuthenticatedRequest(request, complete, diagnostics);
    failCapture(List.of());
    assertThat(diagnostics.snapshot().sawReferer()).isTrue();
    assertThat(diagnostics.snapshot().sawVerificationToken()).isFalse();
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.NO_COMPLETE_REQUEST);
    verify(request, never()).headerValue("x-v-appguid");
    assertFiniteLog(log());
  }

  @ParameterizedTest
  @CsvSource({
    "0,ZERO,ZERO",
    "1,ONE,ONE",
    "2,TWO_TO_FIVE,TWO_PLUS",
    "5,TWO_TO_FIVE,TWO_PLUS",
    "6,SIX_PLUS,TWO_PLUS",
    "100,SIX_PLUS,TWO_PLUS"
  })
  void requestCountsSaturateIntoBoundedBuckets(
      int count,
      SessionCaptureObservation.AllowedRequestCount allowed,
      SessionCaptureObservation.CompleteRequestCount completeCount) {
    for (int index = 0; index < count; index++) observe(REQUEST, REFERER, TOKEN, GUID);
    assertThat(diagnostics.snapshot().allowedRequests()).isEqualTo(allowed);
    assertThat(diagnostics.snapshot().completeRequests()).isEqualTo(completeCount);
  }

  @ParameterizedTest
  @CsvSource({"0,ZERO", "1,ONE", "2,TWO_TO_FOUR", "4,TWO_TO_FOUR", "5,FIVE_PLUS", "100,FIVE_PLUS"})
  void cookieCountOnlyStoresTheBucket(int count, SessionCaptureObservation.CookieCount bucket) {
    diagnostics.cookies(count);
    assertThat(diagnostics.snapshot().cookieCount()).isEqualTo(bucket);
  }

  @Test
  void cookiesFromAnotherOriginCannotMakeACompleteCandidateValid() {
    observe(REQUEST, REFERER, TOKEN, GUID);
    diagnostics.cookies(1);
    failCapture(
        List.of(
            new BrowserCookieObservation(
                URI.create("https://other.vulcan.net.pl/"), COOKIE_NAME, COOKIE_VALUE)));
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.NO_MATCHING_COOKIES);
    assertThat(diagnostics.snapshot().candidates())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.ONE);
    assertThat(diagnostics.snapshot().candidatesWithCookies())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.ZERO);
    assertThat(diagnostics.snapshot().cookieCount())
        .isEqualTo(SessionCaptureObservation.CookieCount.ONE);
  }

  @Test
  void baseDerivationFailurePrecedesCookieProcessingAsBefore() {
    observe("https://school.vulcan.net.pl", REFERER, TOKEN, GUID);
    failCapture(List.of());
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.APPLICATION_BASE_REJECTED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "not a uri SUPER_SECRET_REFERER",
        "https://school.vulcan.net.pl/outside/",
        "https://other.vulcan.net.pl/SECRET_TENANT_PATH/",
        "http://school.vulcan.net.pl/SECRET_TENANT_PATH/",
        "https://school.vulcan.net.pl:443/SECRET_TENANT_PATH/"
      })
  void refererParsingOrExactMaterialUriValidationRejectionIsIdentified(String referer) {
    observe(REQUEST, referer, TOKEN, GUID);
    failCapture(cookies());
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.REFERER_REJECTED);
    assertFiniteLog(log());
  }

  @ParameterizedTest
  @ValueSource(strings = {"token", "appGuid", "cookie"})
  void otherSecretMaterialRejectionsAreClassifiedWithoutInspectingExceptionText(String field) {
    observe(
        REQUEST,
        REFERER,
        field.equals("token") ? TOKEN + "\r\n" : TOKEN,
        field.equals("appGuid") ? GUID + "\r\n" : GUID);
    failCapture(
        field.equals("cookie")
            ? List.of(
                new BrowserCookieObservation(
                    URI.create("https://school.vulcan.net.pl/"), COOKIE_NAME, COOKIE_VALUE + "\n"))
            : cookies());
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.MATERIAL_REJECTED);
    assertThat(diagnostics.snapshot().candidatesWithCookies())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.ONE);
    assertFiniteLog(log());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void failurePrecedenceIsIndependentOfCandidateOrder(boolean reverse) {
    observe(
        "https://other.vulcan.net.pl/app/Call.mvc/Run",
        "https://other.vulcan.net.pl/app/",
        TOKEN,
        GUID);
    observe("https://school.vulcan.net.pl", REFERER, TOKEN, GUID);
    observe(REQUEST, "https://school.vulcan.net.pl/outside/", TOKEN, GUID);
    observe(REQUEST, REFERER, TOKEN + "\n", GUID);
    if (reverse) Collections.reverse(complete);
    failCapture(cookies());
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.MATERIAL_REJECTED);
    assertThat(diagnostics.snapshot().candidates())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.TWO_PLUS);
    assertThat(diagnostics.snapshot().candidatesWithCookies())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.TWO_PLUS);
  }

  @Test
  void eachSpecificRejectionOutranksEarlierValidationSteps() {
    var order =
        List.of(
            SessionCaptureFailureKind.OTHER_PROTOCOL_FAILURE,
            SessionCaptureFailureKind.NO_MATCHING_COOKIES,
            SessionCaptureFailureKind.APPLICATION_BASE_REJECTED,
            SessionCaptureFailureKind.REFERER_REJECTED,
            SessionCaptureFailureKind.MATERIAL_REJECTED);
    for (var reason : order) {
      diagnostics.rejected(reason);
      diagnostics.exhausted();
      assertThat(diagnostics.snapshot().failure()).isEqualTo(reason);
    }
    for (var reason : order.reversed()) diagnostics.rejected(reason);
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.MATERIAL_REJECTED);
  }

  @Test
  void anEarlierValidObservationStillWinsAfterTheNewestCandidateIsRejected() {
    observe(REQUEST, REFERER, TOKEN, GUID);
    observe(REQUEST, REFERER, TOKEN + "\n", GUID);
    var material = new VulcanSessionCapture(urls).capture(complete, cookies(), diagnostics);
    assertThat(material.requestVerificationToken()).isEqualTo(TOKEN);
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.NOT_APPLICABLE);
    assertThat(diagnostics.snapshot().candidates())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.TWO_PLUS);
  }

  @Test
  void newestValidCandidateWinsWithoutInspectingOlderCandidatesOrRetrying() {
    observe(REQUEST, REFERER, TOKEN + "OLDER", GUID);
    observe(REQUEST, REFERER, TOKEN, GUID);
    var older = spy(complete.getFirst());
    complete.set(0, older);
    var material = new VulcanSessionCapture(urls).capture(complete, cookies(), diagnostics);
    assertThat(material.requestVerificationToken()).isEqualTo(TOKEN);
    verifyNoInteractions(older);
    assertThat(diagnostics.snapshot().candidates())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.ONE);
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.NOT_APPLICABLE);
  }

  @Test
  void uriDiagnosticReusesNormalizationAndDoesNotAffectConstructorAcceptance() {
    URI base = URI.create("https://school.vulcan.net.pl/a/../SECRET_TENANT_PATH/");
    assertThat(VulcanSessionMaterial.diagnoseUriValidation(base, URI.create(REFERER)))
        .isEqualTo(VulcanSessionMaterial.UriValidationFailure.NONE);
    assertThatCode(
            () ->
                new VulcanSessionMaterial(
                    base, URI.create(REFERER), TOKEN, GUID, COOKIE_NAME + "=" + COOKIE_VALUE))
        .doesNotThrowAnyException();
    assertThat(
            VulcanSessionMaterial.diagnoseUriValidation(
                URI.create("relative"), URI.create(REFERER)))
        .isEqualTo(VulcanSessionMaterial.UriValidationFailure.APPLICATION_BASE);
  }

  @Test
  void unexpectedAbortIsNotMisreportedAsAnEarlierHandledCandidateRejection() {
    observe(REQUEST, REFERER, TOKEN, GUID);
    observe("https://school.vulcan.net.pl", REFERER, TOKEN, GUID);
    // Existing sameOrigin processing rejects this malformed synthetic cookie by throwing.
    // The newest candidate rejects its base first; that reason must not hide the later abort.
    var malformed =
        List.of(new BrowserCookieObservation(URI.create("relative"), COOKIE_NAME, COOKIE_VALUE));
    assertThatThrownBy(
            () -> new VulcanSessionCapture(urls).capture(complete, malformed, diagnostics))
        .isInstanceOf(NullPointerException.class);
    assertThat(diagnostics.snapshot().failure())
        .isEqualTo(SessionCaptureFailureKind.OTHER_PROTOCOL_FAILURE);
    assertThat(diagnostics.snapshot().candidates())
        .isEqualTo(SessionCaptureObservation.CompleteRequestCount.TWO_PLUS);
  }

  @Test
  void accumulatorAndObservationCannotRetainSecretValuesAndFormatterRejectsStrings()
      throws Exception {
    assertThat(SessionCaptureDiagnostics.class.getDeclaredFields())
        .allSatisfy(
            field ->
                assertThat(field.getType().isPrimitive() || field.getType().isEnum()).isTrue());
    assertThat(SessionCaptureObservation.class.getRecordComponents())
        .allSatisfy(
            component ->
                assertThat(component.getType() == boolean.class || component.getType().isEnum())
                    .isTrue());
    var formatter =
        PlaywrightVulcanBrowserAuthenticator.class.getDeclaredMethod(
            "formatSessionCaptureFailure",
            SessionCaptureObservation.class,
            VulcanAuthFailureCategory.class);
    assertThatThrownBy(
            () -> formatter.invoke(null, DETAILS, VulcanAuthFailureCategory.PROTOCOL_FAILURE))
        .isInstanceOf(IllegalArgumentException.class);
    for (var failure : SessionCaptureFailureKind.values()) {
      for (var allowed : SessionCaptureObservation.AllowedRequestCount.values()) {
        for (var cookie : SessionCaptureObservation.CookieCount.values()) {
          var observation =
              new SessionCaptureObservation(
                  failure,
                  allowed,
                  SessionCaptureObservation.CompleteRequestCount.TWO_PLUS,
                  true,
                  false,
                  true,
                  false,
                  SessionCaptureObservation.CompleteRequestCount.ONE,
                  SessionCaptureObservation.CompleteRequestCount.ZERO,
                  cookie);
          assertFiniteLog(
              PlaywrightVulcanBrowserAuthenticator.formatSessionCaptureFailure(
                  observation, VulcanAuthFailureCategory.PROTOCOL_FAILURE));
        }
      }
    }
  }

  static void assertFiniteLog(String log) {
    assertThat(log)
        .matches(
            "VULCAN browser authentication failed: stage=SESSION_CAPTURE captureFailure=[A-Z_]+"
                + " allowedRequests=[A-Z_]+ completeRequests=[A-Z_]+ sawReferer=(true|false)"
                + " sawVerificationToken=(true|false) sawAppGuid=(true|false) sawAllRequiredHeadersTogether=(true|false)"
                + " candidates=[A-Z_]+ candidatesWithCookies=[A-Z_]+ cookieCount=[A-Z_]+ category=[A-Z_]+");
    assertThat(log)
        .doesNotContain(
            TOKEN,
            GUID,
            COOKIE_NAME,
            COOKIE_VALUE,
            "SUPER_SECRET_REFERER",
            "SECRET_TENANT_PATH",
            "SECRET_QUERY",
            "private exception message",
            "https://",
            "school.vulcan.net.pl");
  }

  static Request request(String uri, String referer, String token, String guid) {
    Request request = mock(Request.class);
    when(request.url()).thenReturn(uri);
    when(request.headerValue("referer")).thenReturn(referer);
    when(request.headerValue("x-v-requestverificationtoken")).thenReturn(token);
    when(request.headerValue("x-v-appguid")).thenReturn(guid);
    return request;
  }

  private void observe(String uri, String referer, String token, String guid) {
    authenticator.observeAuthenticatedRequest(
        request(uri, referer, token, guid), complete, diagnostics);
  }

  private void failCapture(List<BrowserCookieObservation> cookies) {
    assertThatThrownBy(() -> new VulcanSessionCapture(urls).capture(complete, cookies, diagnostics))
        .isInstanceOfSatisfying(
            VulcanAuthenticationException.class,
            failure ->
                assertThat(failure.category())
                    .isEqualTo(VulcanAuthFailureCategory.PROTOCOL_FAILURE));
  }

  private String log() {
    return PlaywrightVulcanBrowserAuthenticator.formatSessionCaptureFailure(
        diagnostics.snapshot(), VulcanAuthFailureCategory.PROTOCOL_FAILURE);
  }

  private List<BrowserCookieObservation> cookies() {
    return List.of(
        new BrowserCookieObservation(
            URI.create("https://school.vulcan.net.pl/"), COOKIE_NAME, COOKIE_VALUE));
  }
}
