package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.playwright;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ElementState;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.PortalUrlValidator;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAuthenticationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Applies one known privacy policy to the page and visible, allowlisted VULCAN frame documents. */
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
    List<ConsentSurface> surfaces = new ArrayList<>();
    Set<Frame> monitoredFrames = new HashSet<>();
    monitoredFrames.add(page.mainFrame());
    Set<Frame> unsafeNavigations = new HashSet<>();
    Consumer<Frame> navigation =
        frame -> {
          if (monitoredFrames.contains(frame) && !allowedUri(frame.url(), portalUrls)) {
            unsafeNavigations.add(frame);
          }
        };
    page.onFrameNavigated(navigation);
    try {
      Locator headings = visibleHeadings(page.getByText(HEADING));
      if (headings.count() > 0) {
        surfaces.add(new ConsentSurface(null, List.of(), List.of(), headings));
      }
      for (Frame frame : page.frames()) {
        if (frame == page.mainFrame()) continue;
        ConsentSurface surface = findFrameConsent(page, frame, portalUrls, monitoredFrames);
        if (surface != null) surfaces.add(surface);
      }
      if (surfaces.isEmpty()) return;
      if (surfaces.size() != 1) throw unsupported();
      dismissTrustedSurface(page, surfaces.getFirst(), portalUrls, unsafeNavigations);
    } finally {
      page.offFrameNavigated(navigation);
      surfaces.forEach(surface -> surface.owners().forEach(VulcanPrivacyConsent::dispose));
    }
  }

  private static Locator visibleHeadings(Locator headings) {
    return headings.filter(new Locator.FilterOptions().setVisible(true));
  }

  private static ConsentSurface findFrameConsent(
      Page page, Frame frame, PortalUrlValidator portalUrls, Set<Frame> monitoredFrames) {
    // URL/ancestry metadata is checked before obtaining owner elements or reading frame DOM.
    List<Frame> ancestry = trustedAncestry(page, frame, portalUrls);
    if (ancestry.isEmpty()) return null;
    monitoredFrames.addAll(ancestry);
    List<ElementHandle> owners = new ArrayList<>();
    boolean retained = false;
    try {
      for (Frame ancestor : ancestry) {
        if (ancestor == page.mainFrame()) continue;
        if (trustedAncestry(page, frame, portalUrls).isEmpty()) throw unsupported();
        ElementHandle owner = ancestor.frameElement();
        owners.add(owner);
        if (!owner.isVisible()) return null;
      }
      if (trustedAncestry(page, frame, portalUrls).isEmpty()) throw unsupported();
      Locator headings = visibleHeadings(frame.getByText(HEADING));
      int count = headings.count();
      if (trustedAncestry(page, frame, portalUrls).isEmpty()) throw unsupported();
      if (count == 0) return null;
      retained = true;
      return new ConsentSurface(frame, ancestry, owners, headings);
    } catch (PlaywrightException exception) {
      // An unrelated frame disappearing during discovery cannot still cover the page.
      if (frame.isDetached()) return null;
      if (trustedAncestry(page, frame, portalUrls).isEmpty()) throw unsupported();
      throw exception;
    } finally {
      if (!retained) owners.forEach(VulcanPrivacyConsent::dispose);
    }
  }

  private static List<Frame> trustedAncestry(
      Page page, Frame frame, PortalUrlValidator portalUrls) {
    requireAllowedPage(page, portalUrls);
    List<Frame> ancestry = new ArrayList<>();
    for (Frame current = frame; current != null; current = current.parentFrame()) {
      if (current.isDetached() || !allowedUri(current.url(), portalUrls)) return List.of();
      ancestry.add(current);
      if (current == page.mainFrame()) return ancestry;
    }
    return List.of();
  }

  private static boolean allowedUri(String value, PortalUrlValidator portalUrls) {
    try {
      return portalUrls.isAllowedRuntimeUri(URI.create(value));
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static URI requireTrustedSurface(
      Page page,
      ConsentSurface surface,
      PortalUrlValidator portalUrls,
      Set<Frame> unsafeNavigations) {
    URI pageUri = requireAllowedPage(page, portalUrls);
    if (unsafeNavigations.contains(page.mainFrame())
        || surface.ancestry().stream().anyMatch(unsafeNavigations::contains)) throw unsupported();
    for (Frame ancestor : surface.ancestry()) {
      if (!ancestor.isDetached() && !allowedUri(ancestor.url(), portalUrls)) throw unsupported();
    }
    if (surface.frame() == null || surface.frame().isDetached()) return pageUri;
    return URI.create(surface.frame().url());
  }

  private static void dismissTrustedSurface(
      Page page,
      ConsentSurface surface,
      PortalUrlValidator portalUrls,
      Set<Frame> unsafeNavigations) {
    requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
    if (surface.detached()) return;
    Locator headings = surface.headings();
    if (headings.count() != 1) throw unsupported();
    requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
    Locator heading = headings.first();
    Locator container = heading.locator(DIALOG);
    if (container.count() == 0) container = heading.locator(FALLBACK_CONTAINER);
    if (container.count() != 1 || !container.isVisible()) throw unsupported();

    requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
    Locator candidates =
        container.locator(ACCEPT_CANDIDATES).filter(new Locator.FilterOptions().setVisible(true));
    // Fail closed on duplicate labels, even if only one of them would pass the safety checks.
    if (candidates.count() != 1) throw unsupported();
    Locator accept = candidates.first();
    if (!accept.isVisible() || accept.locator(UNSAFE_ANCESTOR).count() > 0) throw unsupported();
    requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
    Object formBehavior = accept.evaluate(FORM_BEHAVIOR);
    if (formBehavior != null) {
      if (!(formBehavior instanceof Map<?, ?> form)
          || !isSafeConsentForm(
              requireTrustedSurface(page, surface, portalUrls, unsafeNavigations),
              form,
              portalUrls)) throw unsupported();
    }
    requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
    // Pin the original container: removing only the heading/action must not make a live overlay
    // appear dismissed through re-resolution of the heading-relative locator.
    ElementHandle originalContainer =
        surface.frame() == null
            ? container.elementHandle(
                new Locator.ElementHandleOptions().setTimeout(DISMISS_TIMEOUT_MS))
            : null;
    if (surface.frame() == null && originalContainer == null) throw unsupported();
    try {
      accept.click(new Locator.ClickOptions().setTimeout(DISMISS_TIMEOUT_MS));
      if (surface.frame() == null) {
        originalContainer.waitForElementState(
            ElementState.HIDDEN,
            new ElementHandle.WaitForElementStateOptions().setTimeout(DISMISS_TIMEOUT_MS));
        requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
        if (headings.count() != 0) throw unsupported();
      } else {
        // Hiding the inner dialog alone is insufficient: a transparent iframe can still intercept
        // input. A detached frame or hidden owner (including an ancestor owner) removes the
        // blocker.
        page.waitForCondition(
            () -> noLongerBlocking(page, surface, portalUrls, unsafeNavigations),
            new Page.WaitForConditionOptions().setTimeout(DISMISS_TIMEOUT_MS));
        if (!noLongerBlocking(page, surface, portalUrls, unsafeNavigations)) throw unsupported();
      }
    } catch (PlaywrightException exception) {
      requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
      // A click can detach its own frame before Playwright finishes waiting for the action.
      // Do not inspect stale DOM handles afterward; navigation outside the boundary still fails.
      if (!surface.detached()) throw exception;
    } finally {
      dispose(originalContainer);
    }
  }

  private static boolean noLongerBlocking(
      Page page,
      ConsentSurface surface,
      PortalUrlValidator portalUrls,
      Set<Frame> unsafeNavigations) {
    requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
    if (surface.detached()) return true;
    try {
      for (ElementHandle owner : surface.owners()) {
        if (!owner.isVisible()) return true;
      }
      return false;
    } catch (PlaywrightException exception) {
      requireTrustedSurface(page, surface, portalUrls, unsafeNavigations);
      if (surface.detached()) return true;
      throw exception;
    }
  }

  private static void dispose(ElementHandle handle) {
    if (handle == null) return;
    try {
      handle.dispose();
    } catch (PlaywrightException ignored) {
      // Cleanup of handles whose frame already detached must not replace the sanitized outcome.
    }
  }

  private record ConsentSurface(
      Frame frame, List<Frame> ancestry, List<ElementHandle> owners, Locator headings) {
    boolean detached() {
      return frame != null && frame.isDetached();
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
