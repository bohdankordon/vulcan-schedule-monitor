package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class VulcanSessionCapture {

  private final PortalUrlValidator portalUrls;

  public VulcanSessionCapture(PortalUrlValidator portalUrls) {
    this.portalUrls = portalUrls;
  }

  public VulcanSessionMaterial capture(
      List<BrowserRequestObservation> requests, List<BrowserCookieObservation> cookies) {
    for (int index = requests.size() - 1; index >= 0; index--) {
      BrowserRequestObservation request = requests.get(index);
      String verification = request.requestVerificationToken();
      String appGuid = request.appGuid();
      String referer = request.referer();
      if (!portalUrls.isAllowed(request.uri())
          || isBlank(verification)
          || isBlank(appGuid)
          || isBlank(referer)) {
        continue;
      }
      try {
        URI base = deriveApplicationBase(request.uri());
        URI refererUri = URI.create(referer);
        String cookieHeader =
            cookies.stream()
                .filter(cookie -> sameOrigin(cookie.origin(), base))
                .map(BrowserCookieObservation::headerPair)
                .collect(Collectors.joining("; "));
        if (cookieHeader.isBlank()) {
          continue;
        }
        return new VulcanSessionMaterial(base, refererUri, verification, appGuid, cookieHeader);
      } catch (IllegalArgumentException exception) {
        // This observation is not a safe, complete application request.
      }
    }
    throw new VulcanAuthenticationException(VulcanAuthFailureCategory.PROTOCOL_FAILURE);
  }

  private static URI deriveApplicationBase(URI requestUri) {
    String path = Objects.requireNonNullElse(requestUri.getPath(), "/");
    int mvc = path.indexOf(".mvc/");
    int boundary = mvc < 0 ? path.lastIndexOf('/') : path.lastIndexOf('/', mvc);
    if (boundary < 0) {
      throw new IllegalArgumentException("Application path could not be derived");
    }
    String basePath = path.substring(0, boundary + 1);
    try {
      return new URI(
          requestUri.getScheme(),
          null,
          requestUri.getHost(),
          requestUri.getPort(),
          basePath,
          null,
          null);
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("Application origin is invalid");
    }
  }

  private static boolean sameOrigin(URI left, URI right) {
    return left != null
        && left.getScheme().equalsIgnoreCase(right.getScheme())
        && left.getHost().equalsIgnoreCase(right.getHost())
        && left.getPort() == right.getPort();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
