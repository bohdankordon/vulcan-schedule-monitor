package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import java.net.URI;
import java.util.regex.Pattern;

/** Handles only the known first-party privacy UI in the top-level document. */
final class VulcanPrivacyConsent {
  static final Pattern HEADING =
      Pattern.compile(
          "^[\\s\\u00a0]*Szanujemy[\\s\\u00a0]+Twoją[\\s\\u00a0]+prywatność[\\s\\u00a0]*$");
  static final Pattern ACCEPT =
      Pattern.compile("^[\\s\\u00a0]*Zgadzam[\\s\\u00a0]+się[\\s\\u00a0]*$");
  static final double DISMISS_TIMEOUT_MS = 3_000;

  private VulcanPrivacyConsent() {}

  static void dismissIfPresent(Page page, PortalUrlValidator portalUrls) {
    requireAllowedPage(page, portalUrls);
    Locator headings = page.getByText(HEADING).filter(new Locator.FilterOptions().setVisible(true));
    if (headings.count() != 1) {
      return;
    }
    Locator heading = headings.first();
    Locator acceptButtons =
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(ACCEPT));
    // The nearest container joining the known heading and button also supports modals without ARIA.
    // No iframe traversal, page-wide button selection, or page-provided script execution.
    Locator container =
        heading
            .locator(
                "xpath=ancestor::*[self::dialog or self::div or self::section or @role='dialog' or @aria-modal='true']")
            .filter(new Locator.FilterOptions().setHas(acceptButtons))
            .last();
    if (container.count() == 0 || !container.isVisible()) {
      return;
    }
    Locator accept =
        container
            .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName(ACCEPT))
            .filter(new Locator.FilterOptions().setVisible(true));
    if (accept.count() != 1
        || accept.getAttribute("href") != null
        || accept.getAttribute("formaction") != null) {
      return;
    }
    requireAllowedPage(page, portalUrls);
    accept.click(new Locator.ClickOptions().setTimeout(DISMISS_TIMEOUT_MS));
    container.waitFor(
        new Locator.WaitForOptions()
            .setState(WaitForSelectorState.HIDDEN)
            .setTimeout(DISMISS_TIMEOUT_MS));
    requireAllowedPage(page, portalUrls);
  }

  private static void requireAllowedPage(Page page, PortalUrlValidator portalUrls) {
    try {
      if (portalUrls.isAllowedRuntimeUri(URI.create(page.url()))) {
        return;
      }
    } catch (IllegalArgumentException ignored) {
      // Do not retain the malformed address.
    }
    throw new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
  }
}
