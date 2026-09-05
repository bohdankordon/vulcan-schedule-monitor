package io.github.bohdankordon.vulcanschedulemonitor.vulcan.bootstrap;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.envelopeData;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredArray;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredInt;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredLong;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredText;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.diagnostics.VulcanDiagnostics.CacheFailure;
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
    for (JsonNode period :
        diagnostics.observeCache(
            CacheFailure.PERIODS_SCHEMA, () -> requiredArray(data, "poryLekcji", OPERATION))) {
      long id =
          diagnostics.observeCache(
              CacheFailure.PERIOD_ID_SCHEMA, () -> requiredLong(period, "Id", OPERATION));
      int number =
          diagnostics.observeCache(
              CacheFailure.PERIOD_NUMBER_SCHEMA, () -> requiredInt(period, "Numer", OPERATION));
      String startText =
          diagnostics.observeCache(
              CacheFailure.PERIOD_START_SCHEMA, () -> requiredText(period, "Poczatek", OPERATION));
      LocalTime start =
          parseObservedTime(
              startText,
              CacheFailure.PERIOD_START_TIME_FORMAT,
              CacheFailure.PERIOD_START_TIME_ONLY);
      String endText =
          diagnostics.observeCache(
              CacheFailure.PERIOD_END_SCHEMA, () -> requiredText(period, "Koniec", OPERATION));
      LocalTime end =
          parseObservedTime(
              endText, CacheFailure.PERIOD_END_TIME_FORMAT, CacheFailure.PERIOD_END_TIME_ONLY);
      lessonPeriods.add(
          diagnostics.observeCache(
              CacheFailure.PERIOD_NUMBER_RANGE, () -> new LessonPeriod(id, number, start, end)));
    }
    return diagnostics.observeCache(
        CacheFailure.DUPLICATE_PERIOD_ID, () -> new SchoolBootstrap(schoolYear, lessonPeriods));
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

  private LocalTime parseObservedTime(String value, CacheFailure invalid, CacheFailure timeOnly) {
    try {
      return parseLegacyTime(value);
    } catch (VulcanProtocolException exception) {
      CacheFailure failure = invalid;
      try {
        LocalTime.parse(value);
        failure = timeOnly;
      } catch (java.time.format.DateTimeParseException ignored) {
        // Inspect only format in memory; neither the value nor the exception reaches diagnostics.
      }
      diagnostics.cacheFailure(failure);
      throw exception;
    }
  }
}
