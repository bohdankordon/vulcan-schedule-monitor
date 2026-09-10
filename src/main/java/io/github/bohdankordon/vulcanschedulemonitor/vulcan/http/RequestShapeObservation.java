package io.github.bohdankordon.vulcanschedulemonitor.vulcan.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;

/** Finite request shape booleans and enums without URLs, form bodies, or header values. */
public record RequestShapeObservation(
    RequestMethodShape method,
    RequestContentTypeShape contentType,
    boolean originPresent,
    boolean refererPresent,
    boolean verificationTokenPresent,
    boolean appGuidPresent,
    boolean xRequestedWithPresent,
    boolean cookiePresent) {

  public enum RequestMethodShape {
    POST,
    GET,
    OTHER;

    public static RequestMethodShape fromMethod(HttpMethod method) {
      if (method == null) {
        return OTHER;
      }
      if (HttpMethod.POST.equals(method)) {
        return POST;
      }
      if (HttpMethod.GET.equals(method)) {
        return GET;
      }
      return OTHER;
    }
  }

  public enum RequestContentTypeShape {
    FORM_URLENCODED,
    JSON,
    OTHER,
    NONE;

    public static RequestContentTypeShape fromMediaType(MediaType mediaType) {
      if (mediaType == null) {
        return NONE;
      }
      if (MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType)) {
        return FORM_URLENCODED;
      }
      if (MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)
          || mediaType.getSubtype().endsWith("+json")) {
        return JSON;
      }
      return OTHER;
    }
  }

  public static RequestShapeObservation fromClientRequest(HttpRequest request) {
    if (request == null) {
      return new RequestShapeObservation(
          RequestMethodShape.OTHER,
          RequestContentTypeShape.NONE,
          false,
          false,
          false,
          false,
          false,
          false);
    }
    HttpHeaders headers = request.getHeaders();
    MediaType contentType = headers.getContentType();
    return new RequestShapeObservation(
        RequestMethodShape.fromMethod(request.getMethod()),
        RequestContentTypeShape.fromMediaType(contentType),
        headers.containsHeader(HttpHeaders.ORIGIN),
        headers.containsHeader(HttpHeaders.REFERER),
        headers.containsHeader("X-V-RequestVerificationToken"),
        headers.containsHeader("X-V-AppGuid"),
        headers.containsHeader("X-Requested-With"),
        headers.containsHeader(HttpHeaders.COOKIE));
  }
}
