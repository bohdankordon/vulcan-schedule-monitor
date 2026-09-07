import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs only in the optional Docker smoke target, with networking disabled. */
public final class ChromiumSmoke {
  public static void main(String[] args) {
    if (Runtime.version().feature() != 21) {
      throw new AssertionError("Java 21 required");
    }
    try (var playwright = Playwright.create();
        Browser browser =
            playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        var context = browser.newContext();
        var page = context.newPage()) {
      var requests = new AtomicInteger();
      context.route(
          "**/*",
          route -> {
            requests.incrementAndGet();
            route.abort();
          });
      if (!Files.isExecutable(Path.of(playwright.chromium().executablePath()))) {
        throw new AssertionError("Bundled Chromium executable missing");
      }
      page.navigate("data:text/html,<title>container-smoke</title><button>Offline</button>");
      page.locator("button").click();
      if (!"container-smoke".equals(page.title()) || requests.get() != 0) {
        throw new AssertionError("Offline browser smoke failed");
      }
      System.out.println(
          "Chromium " + browser.version() + ": data URL and click PASS; provider requests=0");
    }
  }
}
