package io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.envelopeData;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredArray;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredInt;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredLong;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredText;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.Stage;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

public final class VulcanBootstrapAdapter {

  private static final String OPERATION = "GetCache";

  private final VulcanSession session;
  private final VulcanHttpTransport transport;
  private final Clock clock;
  private final VulcanDiagnostics diagnostics;

  public VulcanBootstrapAdapter(VulcanSession session, VulcanHttpTransport transport, Clock clock) {
    this(session, transport, clock, VulcanDiagnostics.NONE);
  }

  public VulcanBootstrapAdapter(
      VulcanSession session,
      VulcanHttpTransport transport,
      Clock clock,
      VulcanDiagnostics diagnostics) {
    this.session = Objects.requireNonNull(session, "session must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.diagnostics = Objects.requireNonNull(diagnostics);
  }

  public SchoolBootstrap getCache() {
    diagnostics.begin(Stage.VERIFY_CACHE_REQUEST);
    URI uri =
        UriComponentsBuilder.fromUri(session.resolve("DziennikCache.mvc/GetCache"))
            .queryParam("_dc", clock.millis())
            .build()
            .encode()
            .toUri();
    JsonNode response =
        transport.get(
            OPERATION, uri, diagnostics, Stage.VERIFY_CACHE_REQUEST, Stage.VERIFY_CACHE_PARSE);
    SchoolBootstrap result = mapResponse(response);
    diagnostics.pass(Stage.VERIFY_CACHE_PARSE);
    return result;
  }

  SchoolBootstrap mapResponse(JsonNode response) {
    JsonNode data = envelopeData(response, OPERATION);
    int schoolYear =
        diagnostics.observe(
            Stage.VERIFY_SCHOOL_YEAR, () -> requiredInt(data, "currentSchoolYear", OPERATION));
    diagnostics.begin(Stage.VERIFY_CACHE_PARSE);
    List<LessonPeriod> lessonPeriods = new ArrayList<>();
    for (JsonNode period : requiredArray(data, "poryLekcji", OPERATION)) {
      lessonPeriods.add(
          new LessonPeriod(
              requiredLong(period, "Id", OPERATION),
              requiredInt(period, "Numer", OPERATION),
              parseLegacyTime(requiredText(period, "Poczatek", OPERATION)),
              parseLegacyTime(requiredText(period, "Koniec", OPERATION))));
    }
    return new SchoolBootstrap(schoolYear, lessonPeriods);
  }

  private static LocalTime parseLegacyTime(String value) {
    int separator = value.indexOf('T');
    if (separator < 0 || value.length() < separator + 9) {
      throw new VulcanProtocolException(OPERATION);
    }
    try {
      return LocalTime.parse(value.substring(separator + 1, separator + 9));
    } catch (RuntimeException exception) {
      throw new VulcanProtocolException(OPERATION);
    }
  }
}
