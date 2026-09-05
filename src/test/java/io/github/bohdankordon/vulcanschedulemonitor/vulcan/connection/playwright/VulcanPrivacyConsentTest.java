package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class VulcanPrivacyConsentTest {
  private final Page page = mock(Page.class, RETURNS_DEEP_STUBS);
  private final PortalUrlValidator urls = new PortalUrlValidator();

  @Test
  void recognizesOnlyTheKnownNormalizedPrivacyHeadingAndAcceptance() {
    // Playwright rejects Java-only regex flags such as UNICODE_CHARACTER_CLASS.
    assertThat(VulcanPrivacyConsent.HEADING.flags()).isZero();
    assertThat(VulcanPrivacyConsent.ACCEPT.flags()).isZero();
    assertThat(
            VulcanPrivacyConsent.HEADING.matcher("  Szanujemy\nTwoją\u00a0prywatność ").matches())
        .isTrue();
    assertThat(VulcanPrivacyConsent.ACCEPT.matcher(" Zgadzam\u00a0się ").matches()).isTrue();
    assertThat(VulcanPrivacyConsent.HEADING.matcher("Ustawienia prywatności").matches()).isFalse();
    assertThat(VulcanPrivacyConsent.HEADING.matcher("Nie Szanujemy Twoją prywatność").matches())
        .isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Przejdź do ustawień",
        "Zaloguj się",
        "Zgadzam się na zmianę hasła",
        "Nie zgadzam się",
        "Accept"
      })
  void unrelatedButtonsAreNotConsent(String text) {
    assertThat(VulcanPrivacyConsent.ACCEPT.matcher(text).matches()).isFalse();
  }

  @Test
  void absentConsentIsAnImmediateNoOp() {
    when(page.url()).thenReturn("https://school.vulcan.net.pl/login");
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(page, never()).getByRole(any(), any(Page.GetByRoleOptions.class));
    verify(page, never()).waitForTimeout(anyDouble());
    ArgumentCaptor<Pattern> pattern = ArgumentCaptor.forClass(Pattern.class);
    verify(page).getByText(pattern.capture());
    assertThat(pattern.getValue().matcher("Szanujemy Twoją prywatność").matches()).isTrue();
    ArgumentCaptor<Locator.FilterOptions> filter =
        ArgumentCaptor.forClass(Locator.FilterOptions.class);
    verify(page.getByText(pattern.getValue())).filter(filter.capture());
    assertThat(filter.getValue().visible).isTrue();
  }

  @Test
  void clicksOnlyTheUniqueVisibleScopedAcceptButtonAndWaitsBoundedly() {
    ConsentLocators consent = knownConsent(page);
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    ArgumentCaptor<Locator.GetByRoleOptions> role =
        ArgumentCaptor.forClass(Locator.GetByRoleOptions.class);
    verify(consent.container()).getByRole(eq(AriaRole.BUTTON), role.capture());
    assertThat(((Pattern) role.getValue().name).matcher("Zgadzam się").matches()).isTrue();
    ArgumentCaptor<Locator.ClickOptions> click =
        ArgumentCaptor.forClass(Locator.ClickOptions.class);
    ArgumentCaptor<Locator.WaitForOptions> wait =
        ArgumentCaptor.forClass(Locator.WaitForOptions.class);
    var order = inOrder(consent.accept(), consent.container());
    order.verify(consent.accept()).click(click.capture());
    order.verify(consent.container()).waitFor(wait.capture());
    assertThat(click.getValue().timeout).isEqualTo(3_000);
    assertThat(wait.getValue().timeout).isEqualTo(3_000);
    assertThat(wait.getValue().state).isEqualTo(WaitForSelectorState.HIDDEN);
    verify(page, never()).frameLocator(anyString());
  }

  @Test
  void ambiguousOrAbsentScopedAcceptButtonIsNotClicked() {
    ConsentLocators consent = knownConsent(page);
    when(consent.accept().count()).thenReturn(0, 2);
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
    verify(consent.container(), never()).waitFor(any(Locator.WaitForOptions.class));
  }

  @Test
  void offDomainPageIsRejectedBeforeInspectingConsent() {
    when(page.url()).thenReturn("https://external.example/login");
    assertThatThrownBy(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls))
        .isInstanceOf(VulcanAuthenticationException.class);
    verify(page, never()).getByText(any(Pattern.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"href", "formaction"})
  void consentDoesNotFollowButtonNavigationTargets(String attribute) {
    ConsentLocators consent = knownConsent(page);
    when(consent.accept().getAttribute(attribute)).thenReturn("https://external.example/");
    VulcanPrivacyConsent.dismissIfPresent(page, urls);
    verify(consent.accept(), never()).click(any(Locator.ClickOptions.class));
  }

  @Test
  void aDialogThatDoesNotDismissFailsWithinTheBoundInsteadOfEnteringCredentials() {
    ConsentLocators consent = knownConsent(page);
    doThrow(new TimeoutError("synthetic timeout"))
        .when(consent.container())
        .waitFor(any(Locator.WaitForOptions.class));
    assertThatThrownBy(() -> VulcanPrivacyConsent.dismissIfPresent(page, urls))
        .isInstanceOf(TimeoutError.class);
    verify(consent.accept(), times(1)).click(any(Locator.ClickOptions.class));
  }

  static ConsentLocators knownConsent(Page page) {
    when(page.url()).thenReturn("https://school.vulcan.net.pl/login");
    Locator headings = mock(Locator.class);
    Locator heading = mock(Locator.class, RETURNS_DEEP_STUBS);
    Locator container = mock(Locator.class, RETURNS_DEEP_STUBS);
    Locator accept = mock(Locator.class);
    when(page.getByText(any(Pattern.class)).filter(any(Locator.FilterOptions.class)))
        .thenReturn(headings);
    when(headings.count()).thenReturn(1);
    when(headings.first()).thenReturn(heading);
    when(heading.locator(anyString()).filter(any(Locator.FilterOptions.class)).last())
        .thenReturn(container);
    when(container.count()).thenReturn(1);
    when(container.isVisible()).thenReturn(true);
    when(container
            .getByRole(eq(AriaRole.BUTTON), any(Locator.GetByRoleOptions.class))
            .filter(any(Locator.FilterOptions.class)))
        .thenReturn(accept);
    when(accept.count()).thenReturn(1);
    // A successful click removes the observed UI before the next optional probe.
    doAnswer(
            invocation -> {
              when(headings.count()).thenReturn(0);
              return null;
            })
        .when(accept)
        .click(any(Locator.ClickOptions.class));
    return new ConsentLocators(container, accept);
  }

  record ConsentLocators(Locator container, Locator accept) {}
}
