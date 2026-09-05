package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ElementState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

/** Handles only the known first-party privacy UI in the top-level document. */
final class VulcanPrivacyConsent {
  static final Pattern HEADING =
      Pattern.compile(
          "^[\\s\\u00a0]*Szanujemy[\\s\\u00a0]+Twoją[\\s\\u00a0]+prywatność[\\s\\u00a0]*$");
  static final double DISMISS_TIMEOUT_MS = 3_000;
  static final String DIALOG =
      "xpath=ancestor::*[self::dialog or @role='dialog' or @aria-modal='true'][1]";
  private static final String NORMAL_TEXT = "normalize-space(translate(., '\u00a0', ' '))";
  // The settings action anchors non-ARIA containers independently of the accept action. Never
  // expand a known dialog to an outer page container just to find an unrelated acceptance label.
  static final String FALLBACK_CONTAINER =
      "xpath=ancestor::*[(self::div or self::section) and .//*["
          + NORMAL_TEXT
          + "='Przejdź do ustawień' or (self::input and normalize-space(translate(@value, '\u00a0', ' '))='Przejdź do ustawień')]][1]";
  static final String ACCEPT_CANDIDATES =
      "xpath=.//*[(self::input and "
          + "(translate(@type, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')='button' or "
          + "translate(@type, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')='submit') and "
          + "normalize-space(translate(@value, '\u00a0', ' '))='Zgadzam się') or "
          + "(not(self::input) and "
          + NORMAL_TEXT
          + "='Zgadzam się' and not(.//*["
          + NORMAL_TEXT
          + "='Zgadzam się']))]";
  static final String UNSAFE_ANCESTOR = "xpath=ancestor-or-self::*[@href or @inert or self::label]";
  // Fixed, read-only inspection of effective native form behavior, including ancestor controls
  // when an exact text label is nested in a button. No page-provided script or form values are
  // read.
  static final String FORM_BEHAVIOR =
      """
      element => {
        const control = element.closest('button, input');
        if (!control || !control.form || !['submit', 'image'].includes(control.type)) return null;
        const form = control.form;
        return {
          action: control.hasAttribute('formaction') ? control.formAction : form.action,
          target: (control.hasAttribute('formtarget') ? control.formTarget : form.target)
            || element.ownerDocument.querySelector('base[target]')?.target || '',
          method: control.hasAttribute('formmethod') ? control.formMethod : form.method,
          credentials: Array.from(form.elements).some(field => field.matches(
            "input[type='password'], input[autocomplete='current-password'], input[autocomplete='new-password'], input[autocomplete='username'], input[name='LoginName']"))
        };
      }
      """;

  private VulcanPrivacyConsent() {}

  static void dismissIfPresent(Page page, PortalUrlValidator portalUrls) {
    requireAllowedPage(page, portalUrls);
    Locator headings = page.getByText(HEADING).filter(new Locator.FilterOptions().setVisible(true));
    if (headings.count() == 0) return;
    if (headings.count() != 1) throw unsupported();
    Locator heading = headings.first();
    Locator container = heading.locator(DIALOG);
    if (container.count() == 0) container = heading.locator(FALLBACK_CONTAINER);
    if (container.count() != 1 || !container.isVisible()) throw unsupported();

    Locator candidates =
        container.locator(ACCEPT_CANDIDATES).filter(new Locator.FilterOptions().setVisible(true));
    // Fail closed on duplicate labels, even if only one of them would pass the safety checks.
    if (candidates.count() != 1) throw unsupported();
    Locator accept = candidates.first();
    if (!accept.isVisible() || accept.locator(UNSAFE_ANCESTOR).count() > 0) throw unsupported();
    Object formBehavior = accept.evaluate(FORM_BEHAVIOR);
    if (formBehavior != null) {
      if (!(formBehavior instanceof Map<?, ?> form)
          || !isSafeConsentForm(requireAllowedPage(page, portalUrls), form, portalUrls))
        throw unsupported();
    }
    requireAllowedPage(page, portalUrls);
    // Pin the original container: removing only the heading/action must not make a live overlay
    // appear dismissed through re-resolution of the heading-relative locator.
    ElementHandle originalContainer =
        container.elementHandle(new Locator.ElementHandleOptions().setTimeout(DISMISS_TIMEOUT_MS));
    if (originalContainer == null) throw unsupported();
    try {
      accept.click(new Locator.ClickOptions().setTimeout(DISMISS_TIMEOUT_MS));
      originalContainer.waitForElementState(
          ElementState.HIDDEN,
          new ElementHandle.WaitForElementStateOptions().setTimeout(DISMISS_TIMEOUT_MS));
      requireAllowedPage(page, portalUrls);
      if (headings.count() != 0) throw unsupported();
    } finally {
      originalContainer.dispose();
    }
  }

  static boolean isSafeConsentForm(URI page, Map<?, ?> form, PortalUrlValidator portalUrls) {
    if (!portalUrls.isAllowedRuntimeUri(page)
        || !Boolean.FALSE.equals(form.get("credentials"))
        || !(form.get("target") instanceof String target)
        || !(target.isEmpty() || target.equalsIgnoreCase("_self"))) return false;
    if ("dialog".equals(form.get("method"))) return true;
    if (!("get".equals(form.get("method")) || "post".equals(form.get("method")))) return false;
    if (!(form.get("action") instanceof String action)) return false;
    try {
      URI destination = URI.create(action);
      return portalUrls.isAllowedRuntimeUri(page)
          && portalUrls.isAllowedRuntimeUri(destination)
          && withoutFragment(page).equals(withoutFragment(destination));
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static String withoutFragment(URI uri) {
    return uri.normalize().toASCIIString().split("#", 2)[0];
  }

  private static URI requireAllowedPage(Page page, PortalUrlValidator portalUrls) {
    try {
      URI current = URI.create(page.url());
      if (portalUrls.isAllowedRuntimeUri(current)) return current;
    } catch (IllegalArgumentException ignored) {
      // Do not retain the malformed address.
    }
    throw unsupported();
  }

  private static VulcanAuthenticationException unsupported() {
    return new VulcanAuthenticationException(VulcanAuthFailureCategory.UNSUPPORTED_AUTH_FLOW);
  }
}
