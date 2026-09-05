package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.ContentFamily;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.Stage;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.StatusFamily;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Concurrent-safe HTTP transport for the browser-observed VULCAN protocol. */
public final class VulcanHttpTransport {

  private static final MediaType UTF_8_FORM =
      MediaType.parseMediaType("application/x-www-form-urlencoded; charset=UTF-8");

  private final VulcanSession session;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final RetryAfterParser retryAfterParser;

  public VulcanHttpTransport(VulcanSession session, Duration connectTimeout, Duration readTimeout) {
    this(session, connectTimeout, readTimeout, Clock.systemUTC());
  }

  public VulcanHttpTransport(
      VulcanSession session, Duration connectTimeout, Duration readTimeout, Clock clock) {
    this.session = Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
    Objects.requireNonNull(readTimeout, "readTimeout must not be null");
    Objects.requireNonNull(clock, "clock must not be null");

    HttpClient httpClient =
        session
            .configure(HttpClient.newBuilder())
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(readTimeout);

    this.restClient =
        RestClient.builder()
            .requestFactory(requestFactory)
            .defaultHeaders(session::applyCommonHeaders)
            .build();
    this.objectMapper = new ObjectMapper();
    this.retryAfterParser = new RetryAfterParser(clock);
  }

  public JsonNode get(String operation, URI uri) {
    return exchange(operation, restClient.get().uri(session.validateRequestUri(uri)));
  }

  public JsonNode get(
      String operation,
      URI uri,
      VulcanDiagnostics diagnostics,
      Stage requestStage,
      Stage parseStage) {
    return exchange(
        operation,
        restClient.get().uri(session.validateRequestUri(uri)),
        diagnostics,
        requestStage,
        parseStage);
  }

  public JsonNode postForm(String operation, URI uri, MultiValueMap<String, String> form) {
    return exchange(
        operation,
        restClient
            .post()
            .uri(session.validateRequestUri(uri))
            .header(HttpHeaders.ORIGIN, session.origin())
            .contentType(UTF_8_FORM)
            .body(form));
  }

  private JsonNode exchange(String operation, RestClient.RequestHeadersSpec<?> request) {
    return exchange(operation, request, VulcanDiagnostics.NONE, null, null);
  }

  private JsonNode exchange(
      String operation,
      RestClient.RequestHeadersSpec<?> request,
      VulcanDiagnostics diagnostics,
      Stage requestStage,
      Stage parseStage) {
    try {
      return request.exchange(
          (clientRequest, clientResponse) -> {
            int statusCode = clientResponse.getStatusCode().value();
            if (!clientResponse.getStatusCode().is2xxSuccessful()) {
              if (requestStage != null)
                diagnostics.response(
                    requestStage,
                    statusFamily(statusCode),
                    safeContentFamily(clientResponse.getHeaders()));
              Duration retryAfter =
                  retryAfterParser.parse(
                      clientResponse.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
              throw VulcanHttpException.responseFailure(operation, statusCode, retryAfter);
            }
            MediaType contentType = clientResponse.getHeaders().getContentType();
            if (requestStage != null)
              diagnostics.response(
                  requestStage, statusFamily(statusCode), contentFamily(contentType));
            if (contentType != null && MediaType.TEXT_HTML.isCompatibleWith(contentType)) {
              throw VulcanHttpException.unexpectedHtml(operation);
            }
            if (requestStage != null) {
              diagnostics.pass(requestStage);
              diagnostics.begin(parseStage);
            }
            try {
              JsonNode response = objectMapper.readTree(clientResponse.getBody());
              if (response == null) {
                throw new VulcanProtocolException(operation);
              }
              return response;
            } catch (VulcanProtocolException exception) {
              throw exception;
            } catch (Exception exception) {
              throw new VulcanProtocolException(operation);
            }
          });
    } catch (VulcanHttpException exception) {
      diagnostics.httpFailure(exception.category());
      throw exception;
    } catch (VulcanProtocolException exception) {
      throw exception;
    } catch (RestClientException exception) {
      diagnostics.httpFailure(VulcanFailureCategory.TRANSPORT_ERROR);
      throw VulcanHttpException.transportFailure(operation);
    }
  }

  private static StatusFamily statusFamily(int status) {
    return switch (status / 100) {
      case 1 -> StatusFamily.INFORMATIONAL;
      case 2 -> StatusFamily.SUCCESS;
      case 3 -> StatusFamily.REDIRECT;
      case 4 -> StatusFamily.CLIENT_ERROR;
      case 5 -> StatusFamily.SERVER_ERROR;
      default -> StatusFamily.OTHER;
    };
  }

  private static ContentFamily contentFamily(MediaType type) {
    if (type == null) return ContentFamily.OTHER;
    if (MediaType.TEXT_HTML.isCompatibleWith(type)) return ContentFamily.HTML;
    if (MediaType.APPLICATION_JSON.isCompatibleWith(type) || type.getSubtype().endsWith("+json"))
      return ContentFamily.JSON;
    return ContentFamily.OTHER;
  }

  private static ContentFamily safeContentFamily(HttpHeaders headers) {
    try {
      return contentFamily(headers.getContentType());
    } catch (IllegalArgumentException malformed) {
      return ContentFamily.OTHER;
    }
  }
}
