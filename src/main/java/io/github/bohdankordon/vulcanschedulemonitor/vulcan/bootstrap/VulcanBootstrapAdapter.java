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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

public final class VulcanBootstrapAdapter {

  private static final String OPERATION = "GetCache";
  private static final List<DateTimeFormatter> PERIOD_TIMESTAMP_FORMATTERS =
      List.of(periodTimestampFormatter(' '), periodTimestampFormatter('T'));

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

  private static DateTimeFormatter periodTimestampFormatter(char separator) {
    return new DateTimeFormatterBuilder()
        .appendValue(ChronoField.YEAR, 4)
        .appendPattern("-MM-dd")
        .appendLiteral(separator)
        .appendPattern("HH:mm:ss")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT);
  }

  private static LocalTime parsePeriodTimestamp(String value) {
    for (DateTimeFormatter formatter : PERIOD_TIMESTAMP_FORMATTERS) {
      try {
        return LocalDateTime.parse(value, formatter).toLocalTime();
      } catch (DateTimeParseException ignored) {
        // Try only the two supported whole-timestamp shapes; never expose the parsed input.
      }
    }
    throw new VulcanProtocolException(OPERATION);
  }

  private LocalTime parseObservedTime(String value, CacheFailure invalid, CacheFailure timeOnly) {
    try {
      return parsePeriodTimestamp(value);
    } catch (VulcanProtocolException exception) {
      CacheFailure failure = invalid;
      try {
        LocalTime.parse(value);
        failure = timeOnly;
      } catch (DateTimeParseException ignored) {
        // Inspect only format in memory; neither the value nor the exception reaches diagnostics.
      }
      diagnostics.cacheFailure(failure);
      throw exception;
    }
  }
}
