package io.github.bohdankordon.vulcanschedulemonitor.vulcan.session;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StructuredCookieMaterialTest {
  static final URI BASE = URI.create("https://secret-cookie-domain.invalid/SECRET_COOKIE_PATH/");
  static final VulcanCookieMaterial COOKIE =
      new VulcanCookieMaterial(
          "SUPER_SECRET_COOKIE_NAME",
          "SUPER_SECRET_COOKIE_VALUE",
          "/SECRET_COOKIE_PATH/",
          "secret-cookie-domain.invalid",
          true,
          true);

  static VulcanSessionMaterial material(List<VulcanCookieMaterial> cookies) {
    return VulcanSessionMaterial.structured(
        BASE, BASE, "SUPER_SECRET_TOKEN", "SUPER_SECRET_APPGUID", cookies);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "blank",
        "nameCr",
        "nameSyntax",
        "valueLf",
        "valueControl",
        "valueSeparator",
        "pathRelative",
        "pathCr",
        "pathSeparator",
        "pathBackslash",
        "domainUri",
        "domainPort",
        "domainUser",
        "domainBlank",
        "domainLabel",
        "domainCr",
        "nameSize",
        "valueSize",
        "pathSize",
        "domainSize",
        "utf8Size"
      })
  void rejectsMalformedOrOversizedFieldsWithoutReflectingSecrets(String kind) {
    String name = COOKIE.name(),
        value = COOKIE.value(),
        path = COOKIE.path(),
        domain = COOKIE.domain();
    switch (kind) {
      case "blank" -> name = " ";
      case "nameCr" -> name += "\r";
      case "nameSyntax" -> name += "=";
      case "valueLf" -> value += "\n";
      case "valueControl" -> value += "\0";
      case "valueSeparator" -> value += ";";
      case "pathRelative" -> path = "SECRET_COOKIE_PATH";
      case "pathCr" -> path += "\r";
      case "pathSeparator" -> path += ";";
      case "pathBackslash" -> path += "\\";
      case "domainUri" -> domain = "https://SECRET_COOKIE_DOMAIN.invalid";
      case "domainPort" -> domain += ":443";
      case "domainUser" -> domain = "user@" + domain;
      case "domainBlank" -> domain = "";
      case "domainLabel" -> domain = "SECRET_COOKIE_DOMAIN.invalid";
      case "domainCr" -> domain += "\r";
      case "nameSize" -> name = "n".repeat(257);
      case "valueSize" -> value = "v".repeat(16385);
      case "pathSize" -> path = "/".repeat(4097);
      case "domainSize" -> domain = "d".repeat(254);
      case "utf8Size" -> value = "\u00e9".repeat(8193);
    }
    String n = name, v = value, p = path, d = domain;
    assertThatThrownBy(() -> new VulcanCookieMaterial(n, v, p, d, false, false))
        .hasMessage("Cookie material is invalid")
        .hasNoCause();
  }

  @Test
  void listIsImmutableBoundedAndRejectsDuplicateJdkIdentities() {
    var source = new ArrayList<>(List.of(COOKIE));
    var material = material(source);
    source.clear();
    assertThat(material.cookies()).hasSize(1);
    assertThatThrownBy(() -> material.cookies().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    for (var invalid :
        List.of(
            List.<VulcanCookieMaterial>of(),
            List.of(COOKIE, COOKIE),
            Collections.nCopies(1001, COOKIE)))
      assertThatThrownBy(() -> material(invalid))
          .hasMessage("Cookie material is invalid")
          .hasNoCause();
    var differentCase =
        new VulcanCookieMaterial(
            COOKIE.name().toLowerCase(Locale.ROOT),
            COOKIE.value(),
            COOKIE.path(),
            COOKIE.domain(),
            true,
            true);
    assertThatThrownBy(() -> material(List.of(COOKIE, differentCase)))
        .hasMessage("Cookie material is invalid");
  }

  @Test
  void equalityIncludesAllSixFieldsButIgnoresOrderingAndOutputsOnlyFiniteFacts() {
    var other =
        new VulcanCookieMaterial(
            COOKIE.name(), "other", "/", ".secret-cookie-domain.invalid", false, false);
    assertThat(material(List.of(COOKIE, other)).sameCookiesAs(material(List.of(other, COOKIE))))
        .isTrue();
    for (var changed :
        List.of(
            new VulcanCookieMaterial(
                "other", COOKIE.value(), COOKIE.path(), COOKIE.domain(), true, true),
            new VulcanCookieMaterial(
                COOKIE.name(), "other", COOKIE.path(), COOKIE.domain(), true, true),
            new VulcanCookieMaterial(
                COOKIE.name(), COOKIE.value(), "/", COOKIE.domain(), true, true),
            new VulcanCookieMaterial(
                COOKIE.name(),
                COOKIE.value(),
                COOKIE.path(),
                ".secret-cookie-domain.invalid",
                true,
                true),
            new VulcanCookieMaterial(
                COOKIE.name(), COOKIE.value(), COOKIE.path(), COOKIE.domain(), false, true),
            new VulcanCookieMaterial(
                COOKIE.name(), COOKIE.value(), COOKIE.path(), COOKIE.domain(), true, false))) {
      var comparison =
          SessionFidelityDiagnostics.compare(material(List.of(COOKIE)), material(List.of(changed)));
      assertThat(comparison.cookieMaterialSame()).isFalse();
      safe(comparison.toString());
    }
    safe(COOKIE.toString() + material(List.of(COOKIE)) + material(List.of(COOKIE)).cookies());
  }

  @Test
  void nullDomainIsUriAssociatedSecureFlagRestrictsTransportAndUnknownPathNeverBroadensRouting()
      throws Exception {
    var nullDomain =
        new VulcanCookieMaterial(COOKIE.name(), COOKIE.value(), "/", null, false, true);
    var manager = new CookieManager();
    manager.getCookieStore().add(BASE, nullDomain.toCookie());
    assertThat(manager.get(BASE, Map.of()).get("Cookie").isEmpty()).isFalse();
    assertThat(manager.get(URI.create("https://other.invalid/"), Map.of()).get("Cookie")).isEmpty();
    manager.getCookieStore().removeAll();
    manager.getCookieStore().add(BASE, COOKIE.toCookie());
    assertThat(
            manager
                .get(
                    URI.create("http://secret-cookie-domain.invalid/SECRET_COOKIE_PATH/"), Map.of())
                .get("Cookie"))
        .isEmpty();
    var unknownPath =
        new VulcanCookieMaterial(COOKIE.name(), COOKIE.value(), null, null, false, false);
    var restored = VulcanSession.fromMaterial(material(List.of(unknownPath))).snapshotMaterial();
    assertThat(restored.sameCookiesAs(material(List.of(unknownPath)))).isTrue();
    assertThat(unknownPath.toCookie().getMaxAge()).isEqualTo(-1);
  }

  @Test
  void differentDomainIdentitiesSurviveAndRetainJdkDomainRoutingWithoutNetwork() throws Exception {
    URI base = URI.create("https://sub.secret-cookie-domain.invalid/SECRET_COOKIE_PATH/");
    var parent =
        new VulcanCookieMaterial(
            COOKIE.name(), "parent", "/", ".secret-cookie-domain.invalid", false, true);
    var child =
        new VulcanCookieMaterial(
            COOKIE.name(), "child", "/", ".sub.secret-cookie-domain.invalid", false, true);
    var material =
        VulcanSessionMaterial.structured(
            base, base, "SUPER_SECRET_TOKEN", "SUPER_SECRET_APPGUID", List.of(parent, child));
    var session = VulcanSession.fromMaterial(material);
    assertThat(material.sameCookiesAs(session.snapshotMaterial())).isTrue();
    assertThat(SessionFidelityDiagnostics.topology(session).duplicateNameDifferentDomainPresent())
        .isTrue();
    try (var client = session.configure(java.net.http.HttpClient.newBuilder()).build()) {
      var handler = client.cookieHandler().orElseThrow();
      var both = String.join("; ", handler.get(base, Map.of()).get("Cookie"));
      var sibling =
          String.join(
              "; ",
              handler
                  .get(URI.create("https://sibling.secret-cookie-domain.invalid/"), Map.of())
                  .get("Cookie"));
      assertThat(
              both.contains(COOKIE.name() + "=parent") && both.contains(COOKIE.name() + "=child"))
          .isTrue();
      assertThat(sibling.contains(COOKIE.name() + "=parent")).isTrue();
      assertThat(sibling.contains(COOKIE.name() + "=child")).isFalse();
    }
  }

  @Test
  void exactFieldLimitsAreAcceptedAndExpiryIsNotPersistedAsRelativeMaxAge() {
    var maximum =
        new VulcanCookieMaterial(
            "n".repeat(256),
            "v".repeat(16384),
            "/".repeat(4096),
            "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61),
            false,
            false);
    assertThat(maximum.toCookie().getMaxAge()).isEqualTo(-1);
    var live = COOKIE.toCookie();
    live.setMaxAge(123);
    assertThat(VulcanCookieMaterial.fromCookie(live).toCookie().getMaxAge()).isEqualTo(-1);
    var cookies = new ArrayList<VulcanCookieMaterial>();
    for (int i = 0; i < 1000; i++)
      cookies.add(new VulcanCookieMaterial("n" + i, "v", "/", null, false, false));
    assertThat(material(cookies).cookieCount()).isEqualTo(1000);
  }

  private static void safe(String output) {
    assertThat(output)
        .doesNotContain(
            "SUPER_SECRET",
            "SECRET_COOKIE_PATH",
            "SECRET_COOKIE_DOMAIN",
            "secret-cookie-domain.invalid");
  }
}
