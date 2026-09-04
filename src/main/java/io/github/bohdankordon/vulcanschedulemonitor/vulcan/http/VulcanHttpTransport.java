package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.net.http.HttpClient;
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

  public VulcanHttpTransport(VulcanSession session, Duration connectTimeout, Duration readTimeout) {
    this.session = Objects.requireNonNull(session, "session must not be null");
    Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
    Objects.requireNonNull(readTimeout, "readTimeout must not be null");

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
  }

  public JsonNode get(String operation, URI uri) {
    return exchange(operation, restClient.get().uri(session.validateRequestUri(uri)));
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
    try {
      return request.exchange(
          (clientRequest, clientResponse) -> {
            int statusCode = clientResponse.getStatusCode().value();
            if (!clientResponse.getStatusCode().is2xxSuccessful()) {
              throw VulcanHttpException.responseFailure(operation, statusCode);
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
    } catch (VulcanHttpException | VulcanProtocolException exception) {
      throw exception;
    } catch (RestClientException exception) {
      throw VulcanHttpException.transportFailure(operation);
    }
  }
}
