package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.SessionMaterialTestSupport.cookiePairs;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionMaterialTestSupportTest {
  private static final URI BASE = URI.create("http://127.0.0.1/SECRET_TENANT_PATH/");

  static VulcanSessionMaterial material(String cookies) {
    return new VulcanSessionMaterial(
        BASE,
        BASE.resolve("SUPER_SECRET_REFERER"),
        "SUPER_SECRET_TOKEN",
        "SUPER_SECRET_APPGUID",
        cookies);
  }

  @Test
  void cookieOnlyDifferenceAndOrderingHaveExactMultisetSemantics() {
    var a = material("SECRET_NAME=SUPER_SECRET_COOKIE; second=B");
    var reordered = material("second=B; SECRET_NAME=SUPER_SECRET_COOKIE");
    assertThat(SessionMaterialTestSupport.compare(a, reordered).allSame()).isTrue();
    var comparison = SessionMaterialTestSupport.compare(a, material("SECRET_NAME=CHANGED"));
    assertThat(comparison.applicationBaseSame()).isTrue();
    assertThat(comparison.refererSame()).isTrue();
    assertThat(comparison.verificationTokenSame()).isTrue();
    assertThat(comparison.appGuidSame()).isTrue();
    assertThat(comparison.cookieMaterialSame()).isFalse();
    assertThat(comparison.expectedCookieCount()).isEqualTo(2);
    assertThat(comparison.actualCookieCount()).isEqualTo(1);
    assertThat(comparison.cookieCountChanged()).isTrue();
    safe(comparison.toString());
    assertThat(
            SessionMaterialTestSupport.compare(
                    a, material("SECRET_NAME=SUPER_SECRET_COOKIE; second=B; second=B"))
                .allSame())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void eachNonCookieFieldIsComparedIndependently(int changed) {
    var original = material("SECRET_NAME=SUPER_SECRET_COOKIE");
    var other =
        new VulcanSessionMaterial(
            changed == 0 ? BASE.resolve("../") : BASE,
            changed == 1 ? BASE.resolve("another") : original.refererUri(),
            changed == 2 ? "other" : original.requestVerificationToken(),
            changed == 3 ? "other" : original.appGuid(),
            cookiePairs(original));
    var c = SessionMaterialTestSupport.compare(original, other);
    assertThat(
            List.of(
                c.applicationBaseSame(),
                c.refererSame(),
                c.verificationTokenSame(),
                c.appGuidSame()))
        .isEqualTo(java.util.stream.IntStream.range(0, 4).mapToObj(i -> i != changed).toList());
    assertThat(c.cookieMaterialSame()).isTrue();
    assertThat(c.cookieCountChanged()).isFalse();
    safe(c.toString());
  }

  @Test
  void uniqueNamesSurviveAndHaveNoDuplicateTopology() {
    var session = VulcanSession.fromMaterial(material("SECRET_NAME=SUPER_SECRET_COOKIE; second=B"));
    var topology = SessionMaterialTestSupport.topology(session);
    assertThat(topology)
        .isEqualTo(new SessionMaterialTestSupport.CookieTopology(2, false, false, false));
    assertThat(SessionMaterialTestSupport.roundTrip(session.snapshotMaterial()))
        .isEqualTo(new SessionMaterialTestSupport.MaterialRoundTrip(true, 2, 2));
    safe(topology.toString());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void realJdkSetCookieDifferentPathSurvivesAndSamePathReplacesDeterministically(
      boolean differentPath) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          try (exchange) {
            exchange
                .getResponseHeaders()
                .add(
                    "Set-Cookie",
                    "SECRET_NAME=SUPER_SECRET_COOKIE_NEW; Path="
                        + (differentPath ? "/" : "/SECRET_TENANT_PATH/"));
            exchange.sendResponseHeaders(204, -1);
          }
        });
    server.start();
    try {
      URI base =
          URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/SECRET_TENANT_PATH/");
      var session =
          VulcanSession.fromMaterial(
              new VulcanSessionMaterial(
                  base,
                  base,
                  "SUPER_SECRET_TOKEN",
                  "SUPER_SECRET_APPGUID",
                  "SECRET_NAME=SUPER_SECRET_COOKIE_OLD"));
      try (var client = session.configure(HttpClient.newBuilder()).build()) {
        assertThat(
                client
                    .send(
                        HttpRequest.newBuilder(base).build(),
                        HttpResponse.BodyHandlers.discarding())
                    .statusCode())
            .isEqualTo(204);
      }
      var topology = SessionMaterialTestSupport.topology(session);
      assertThat(topology)
          .isEqualTo(
              new SessionMaterialTestSupport.CookieTopology(
                  differentPath ? 2 : 1, differentPath, differentPath, false));
      var snapshot = session.snapshotMaterial();
      var roundTrip = SessionMaterialTestSupport.roundTrip(snapshot);
      assertThat(roundTrip)
          .isEqualTo(
              new SessionMaterialTestSupport.MaterialRoundTrip(
                  true, differentPath ? 2 : 1, differentPath ? 2 : 1));
      assertThat(cookiePairs(snapshot).contains("SUPER_SECRET_COOKIE_NEW")).isTrue();
      assertThat(cookiePairs(snapshot).contains("SUPER_SECRET_COOKIE_OLD"))
          .isEqualTo(differentPath);
      assertThat(calls.get()).isEqualTo(1); // No retry and reconstruction performs no request.
      safe(topology.toString() + roundTrip);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void jdkCookieManagerCanRetainSameNameAcrossDomainsWithoutNetwork() throws Exception {
    var manager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    URI uri = URI.create("https://sub.SECRET_DOMAIN.invalid/SECRET_PATH/");
    manager.put(
        uri,
        Map.of(
            "Set-Cookie",
            List.of(
                "SECRET_NAME=SUPER_SECRET_COOKIE_A; Domain=SECRET_DOMAIN.invalid; Path=/",
                "SECRET_NAME=SUPER_SECRET_COOKIE_B; Domain=sub.SECRET_DOMAIN.invalid; Path=/")));
    var topology = CookieTopologyObservation.observe(manager.getCookieStore().getCookies());
    assertThat(topology).isEqualTo(new CookieTopologyObservation(2, true, false, true));
    safe(topology.toString());
  }

  @Test
  void countsSaturateButComparisonAndTopologyInspectBeyondTheBound() {
    var cookies = new ArrayList<HttpCookie>();
    for (int i = 0; i < 1001; i++)
      cookies.add(new HttpCookie("SECRET_NAME" + i, "SUPER_SECRET_COOKIE"));
    var duplicate = new HttpCookie("SECRET_NAME0", "SUPER_SECRET_COOKIE");
    duplicate.setPath("/SECRET_PATH/");
    cookies.add(duplicate);
    assertThat(CookieTopologyObservation.observe(cookies))
        .isEqualTo(new CookieTopologyObservation(1000, true, true, false));
    String pairs = String.join("; ", Collections.nCopies(1001, "SECRET_NAME=SUPER_SECRET_COOKIE"));
    var comparison =
        SessionMaterialTestSupport.compare(material(pairs), material(pairs + "; extra=B"));
    assertThat(comparison.expectedCookieCount()).isEqualTo(1000);
    assertThat(comparison.actualCookieCount()).isEqualTo(1000);
    assertThat(comparison.cookieCountChanged()).isTrue();
    assertThat(comparison.cookieMaterialSame()).isFalse();
    safe(comparison.toString());
  }

  private static void safe(String output) {
    assertThat(output)
        .doesNotContain(
            "SECRET_NAME",
            "SUPER_SECRET",
            "SECRET_TENANT_PATH",
            "SECRET_PATH",
            "SECRET_DOMAIN",
            "http://",
            "https://");
  }
}
