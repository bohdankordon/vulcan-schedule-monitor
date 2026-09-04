package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import java.net.URI;
import java.util.Arrays;
import java.util.Objects;

public final class VulcanLoginRequest implements AutoCloseable {

  private final URI portalUri;
  private final String login;
  private final char[] password;

  public VulcanLoginRequest(URI portalUri, String login, char[] password) {
    this.portalUri = Objects.requireNonNull(portalUri, "portalUri must not be null");
    if (login == null || login.isBlank() || password == null || password.length == 0) {
      throw new IllegalArgumentException("Login and password are required");
    }
    this.login = login;
    this.password = password.clone();
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
    return "VulcanLoginRequest[credentials=[redacted]]";
  }
}
