package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public final class PortalUrlValidator {

  public URI validate(String submittedUrl) {
    final URI uri;
    try {
      uri = URI.create(submittedUrl == null ? "" : submittedUrl.trim());
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
    if (!isAllowedRuntimeUri(uri) || uri.getFragment() != null) {
      throw invalid();
    }
    return uri.normalize();
  }

  /** Runtime queries and fragments do not change the approved network destination. */
  public boolean isAllowedRuntimeUri(URI uri) {
    if (uri == null
        || !uri.isAbsolute()
        || !"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)) {
      return false;
    }
    String normalizedHost = uri.getHost().toLowerCase(Locale.ROOT);
    return normalizedHost.equals("vulcan.net.pl") || normalizedHost.endsWith(".vulcan.net.pl");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Enter a valid HTTPS VULCAN portal address");
  }
}
