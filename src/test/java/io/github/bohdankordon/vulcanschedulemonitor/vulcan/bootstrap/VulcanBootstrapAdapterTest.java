package io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap;

import static io.github.bohdankordon.vulcanschedulemonitor.testsupport.VulcanFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class VulcanBootstrapAdapterTest {

  @Test
  void mapsPublicLegacySpaceSeparatedTimestampsToLocalLessonTimes() {
    SchoolBootstrap bootstrap = adapter().mapResponse(json("get-cache-legacy-space"));

    assertThat(bootstrap.currentSchoolYear()).isEqualTo(2099);
    assertThat(bootstrap.lessonPeriods())
        .containsExactly(new LessonPeriod(501, 1, LocalTime.of(8, 0), LocalTime.of(8, 45)));
  }

  @Test
  void mapsOpaquePeriodIdentifiersToTheirDeclaredLessonNumbersAndTimes() {
    VulcanBootstrapAdapter adapter = adapter();

    SchoolBootstrap bootstrap = adapter.mapResponse(json("get-cache"));

    assertThat(bootstrap.currentSchoolYear()).isEqualTo(2099);
    assertThat(bootstrap.lessonPeriod(1427))
        .contains(new LessonPeriod(1427, 0, LocalTime.of(7, 5), LocalTime.of(7, 50)));
    assertThat(bootstrap.lessonPeriod(501))
        .contains(new LessonPeriod(501, 1, LocalTime.of(8, 0), LocalTime.of(8, 45)));
    assertThat(bootstrap.lessonPeriod(850)).map(LessonPeriod::number).contains(2);
  }

  @Test
  void rejectsNegativeLessonNumber() {
    assertThatThrownBy(() -> new LessonPeriod(1603, -1, LocalTime.of(6, 10), LocalTime.of(6, 55)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Lesson period number must not be negative");
  }

  private static VulcanBootstrapAdapter adapter() {
    VulcanSession session = session();
    return new VulcanBootstrapAdapter(
        session,
        new VulcanHttpTransport(session, Duration.ofSeconds(1), Duration.ofSeconds(1)),
        Clock.systemUTC());
  }

  private static VulcanSession session() {
    return VulcanSession.fromBrowserSession(
        URI.create("https://example.invalid/app/"),
        "synthetic-token",
        "synthetic-app",
        "synthetic-cookie=value");
  }
}
