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
    String host = uri.getHost();
    if (!uri.isAbsolute()
        || !"https".equalsIgnoreCase(uri.getScheme())
        || host == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)) {
      throw invalid();
    }
    String normalizedHost = host.toLowerCase(Locale.ROOT);
    if (!(normalizedHost.equals("vulcan.net.pl") || normalizedHost.endsWith(".vulcan.net.pl"))) {
      throw invalid();
    }
    return uri.normalize();
  }

  public boolean isAllowed(URI uri) {
    try {
      validate(uri == null ? null : uri.toASCIIString());
      return true;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Enter a valid HTTPS VULCAN portal address");
  }
}
