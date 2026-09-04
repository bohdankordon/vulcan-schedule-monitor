package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.web;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.ConnectOutcome;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionProperties;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence.JpaVulcanConnectTokenService;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.ConnectTokenState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token.RawConnectToken;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@ConditionalOnProperty(name = "vulcan.connection.enabled", havingValue = "true")
public class VulcanConnectController {

  static final String TOKEN_COOKIE = "VULCAN_CONNECT_TOKEN";

  private final JpaVulcanConnectTokenService tokens;
  private final VulcanConnectionService connectionService;
  private final VulcanConnectionProperties properties;

  VulcanConnectController(
      JpaVulcanConnectTokenService tokens,
      VulcanConnectionService connectionService,
      VulcanConnectionProperties properties) {
    this.tokens = tokens;
    this.connectionService = connectionService;
    this.properties = properties;
  }

  @GetMapping("/connect/{token}")
  ResponseEntity<Void> exchangeToken(@PathVariable String token) {
    boolean valid;
    try {
      valid = tokens.validate(new RawConnectToken(token)).state() == ConnectTokenState.VALID;
    } catch (IllegalArgumentException exception) {
      valid = false;
    }
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .header(HttpHeaders.LOCATION, valid ? "/connect" : "/connect?invalid")
        .header(
            HttpHeaders.SET_COOKIE,
            valid ? tokenCookie(token).toString() : clearCookie().toString())
        .build();
  }

  @GetMapping("/connect")
  String form(
      @CookieValue(name = TOKEN_COOKIE, required = false) String token,
      Model model,
      jakarta.servlet.http.HttpServletResponse response) {
    if (!valid(token)) {
      response.addHeader(HttpHeaders.SET_COOKIE, clearCookie().toString());
      return "connect-unavailable";
    }
    return "connect-form";
  }

  @PostMapping("/connect")
  String connect(
      @CookieValue(name = TOKEN_COOKIE, required = false) String token,
      @RequestParam String portalUrl,
      @RequestParam String login,
      @RequestParam String password,
      @RequestParam(defaultValue = "false") boolean rememberCredentials,
      Model model,
      jakarta.servlet.http.HttpServletResponse response) {
    if (!valid(token)) {
      response.addHeader(HttpHeaders.SET_COOKIE, clearCookie().toString());
      return "connect-unavailable";
    }
    ConnectOutcome outcome =
        connectionService.connect(
            new RawConnectToken(token),
            portalUrl,
            login,
            password.toCharArray(),
            rememberCredentials);
    if (outcome.status() == ConnectOutcome.Status.SUCCESS) {
      response.addHeader(HttpHeaders.SET_COOKIE, clearCookie().toString());
      model.addAttribute("classCount", outcome.classCount());
      return "connect-success";
    }
    if (!outcome.retryAllowed()) {
      response.addHeader(HttpHeaders.SET_COOKIE, clearCookie().toString());
      return "connect-unavailable";
    }
    model.addAttribute("errorMessage", messageFor(outcome.status()));
    return "connect-form";
  }

  private boolean valid(String token) {
    if (token == null || token.isBlank()) {
      return false;
    }
    try {
      return tokens.validate(new RawConnectToken(token)).state() == ConnectTokenState.VALID;
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private ResponseCookie tokenCookie(String value) {
    return ResponseCookie.from(TOKEN_COOKIE, value)
        .httpOnly(true)
        .secure(secureCookie())
        .sameSite("Strict")
        .path("/connect")
        .maxAge(properties.getTokenTtl())
        .build();
  }

  private ResponseCookie clearCookie() {
    return ResponseCookie.from(TOKEN_COOKIE, "")
        .httpOnly(true)
        .secure(secureCookie())
        .sameSite("Strict")
        .path("/connect")
        .maxAge(Duration.ZERO)
        .build();
  }

  private boolean secureCookie() {
    URI publicBase = properties.getPublicBaseUrl();
    return publicBase != null && "https".equalsIgnoreCase(publicBase.getScheme());
  }

  private static String messageFor(ConnectOutcome.Status status) {
    return switch (status) {
      case INVALID_PORTAL -> "Enter the HTTPS address of your VULCAN portal.";
      case INVALID_CREDENTIALS -> "The login or password was not accepted. Please try again.";
      case UNSUPPORTED_AUTH_FLOW ->
          "This login method requires MFA, CAPTCHA, or another unsupported flow. "
              + "No security control will be bypassed.";
      case TRANSIENT_FAILURE -> "VULCAN is temporarily unavailable. Please try again later.";
      case PROTOCOL_FAILURE -> "The authenticated VULCAN session could not be verified.";
      case TOKEN_INVALID, SUCCESS -> "This connection link is no longer available.";
    };
  }
}
