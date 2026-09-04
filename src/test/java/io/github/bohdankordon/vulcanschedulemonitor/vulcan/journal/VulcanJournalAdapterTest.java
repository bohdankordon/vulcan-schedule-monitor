package io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal;

import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class VulcanJournalAdapterTest {

  @Test
  void recursivelyDiscoversOnlyExistingJournalsInIrregularChildren() {
    VulcanJournalAdapter adapter = adapter();

    List<SchoolClass> classes = adapter.mapResponse(json("get-tree"));

    assertThat(classes)
        .containsExactly(
            new SchoolClass(
                4201,
                4301,
                "4Z",
                "SYN",
                4,
                2099,
                LocalDate.of(2099, 9, 1),
                LocalDate.of(2100, 6, 30)));
  }

  private static VulcanJournalAdapter adapter() {
    VulcanSession session =
        VulcanSession.fromBrowserSession(
            URI.create("https://example.invalid/app/"),
            "synthetic-token",
            "synthetic-app",
            "synthetic-cookie=value");
    return new VulcanJournalAdapter(
        session,
        new VulcanHttpTransport(session, Duration.ofSeconds(1), Duration.ofSeconds(1)),
        Clock.systemUTC());
  }
}
