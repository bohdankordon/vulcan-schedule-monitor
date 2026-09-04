package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

public final class RememberedCredentials implements AutoCloseable {

  private final URI portalUri;
  private final String login;
  private final char[] password;

  public RememberedCredentials(URI portalUri, String login, char[] password) {
    this.portalUri = Objects.requireNonNull(portalUri, "portalUri must not be null");
    this.login = Objects.requireNonNull(login, "login must not be null");
    this.password = Objects.requireNonNull(password, "password must not be null").clone();
  }

  public URI portalUri() {
    return portalUri;
  }

  public String login() {
    return login;
  }

  public char[] password() {
    return password.clone();
  }

  @Override
  public void close() {
    Arrays.fill(password, '\0');
  }

  @Override
  public String toString() {
    return "RememberedCredentials[credentials=[redacted]]";
  }
}
