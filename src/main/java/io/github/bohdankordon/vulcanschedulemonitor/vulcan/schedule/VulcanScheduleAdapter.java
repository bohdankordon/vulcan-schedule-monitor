package io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.booleanOrFalse;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.envelopeData;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.nullableLong;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredArray;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredLong;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredText;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ChangeSignal;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.LessonChangeContext;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChangeParser;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.UnknownScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.JsonNode;

public final class VulcanScheduleAdapter {

  private static final String OPERATION = "GetPlanLekcjiContext";
  private static final DateTimeFormatter REQUEST_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private final VulcanSession session;
  private final VulcanHttpTransport transport;
  private final ScheduleChangeParser changeParser;

  public VulcanScheduleAdapter(VulcanSession session, VulcanHttpTransport transport) {
    this.session = Objects.requireNonNull(session, "session must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.changeParser = new ScheduleChangeParser();
  }

  public ScheduleSnapshot getWeekSchedule(long journalId, LocalDate dateWithinWeek) {
    Objects.requireNonNull(dateWithinWeek, "dateWithinWeek must not be null");
    LocalDate weekStart = dateWithinWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    LocalDate weekEnd = weekStart.plusDays(6);

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("dataOd", formatDate(weekStart));
    form.add("dataDo", formatDate(weekEnd));
    form.add("idDziennik", Long.toString(journalId));
    form.add("data", formatDate(dateWithinWeek));

    JsonNode response =
        transport.postForm(OPERATION, session.resolve("PlanLekcji.mvc/GetPlanLekcjiContext"), form);
    return mapResponse(journalId, weekStart, weekEnd, response);
  }

  ScheduleSnapshot mapResponse(
      long journalId, LocalDate weekStart, LocalDate weekEnd, JsonNode response) {
    JsonNode data = envelopeData(response, OPERATION);
    List<BaseRow> baseRows = mapBaseRows(requiredArray(data, "planLekcji", OPERATION));
    List<EffectiveRow> effectiveRows =
        mapEffectiveRows(requiredArray(data, "planLekcjiZeZmianami", OPERATION));

    Map<Long, BaseRow> baseById = new HashMap<>();
    for (BaseRow base : baseRows) {
      if (baseById.putIfAbsent(base.rowId(), base) != null) {
        throw new VulcanProtocolException(OPERATION);
      }
    }

    List<LessonOccurrence> occurrences = new ArrayList<>();
    List<ScheduleChange> changes = new ArrayList<>();
    Set<Long> correlatedBaseIds = new HashSet<>();

    for (EffectiveRow effective : effectiveRows) {
      occurrences.add(effective.occurrence());
      BaseRow base = baseById.get(effective.baseRowId());
      if (base != null) {
        correlatedBaseIds.add(base.rowId());
      }
      EnumSet<ChangeSignal> signals = changeSignals(base, effective);
      LessonChangeContext context = changeContext(base, effective);
      changes.addAll(changeParser.parse(context, effective.annotations(), signals));
    }

    if (effectiveRows.isEmpty()) {
      baseRows.stream().map(BaseRow::occurrence).forEach(occurrences::add);
    }
    for (BaseRow base : baseRows) {
      if (base.changeMarked() && !correlatedBaseIds.contains(base.rowId())) {
        changes.add(
            new UnknownScheduleChange(
                LessonChangeContext.plannedOnly(base.occurrence()),
                Set.of(ChangeSignal.BASE_MARKER),
                List.of()));
      }
    }

    return new ScheduleSnapshot(journalId, weekStart, weekEnd, occurrences, changes);
  }

  private static List<BaseRow> mapBaseRows(JsonNode rows) {
    List<BaseRow> mapped = new ArrayList<>();
    for (JsonNode row : rows) {
      mapped.add(
          new BaseRow(
              requiredLong(row, "Id", OPERATION),
              parseDate(requiredText(row, "Dzien", OPERATION)),
              requiredLong(row, "IdPoraLekcji", OPERATION),
              nullableLong(row, "IdPodzial", OPERATION),
              nullableLong(row, "IdPrzedmiot", OPERATION),
              nullableLong(row, "IdPracownik", OPERATION),
              nullableLong(row, "IdSala", OPERATION),
              booleanOrFalse(row, "CzyZmiana", OPERATION)));
    }
    return mapped;
  }

  private static List<EffectiveRow> mapEffectiveRows(JsonNode rows) {
    List<EffectiveRow> mapped = new ArrayList<>();
    for (JsonNode row : rows) {
      mapped.add(
          new EffectiveRow(
              nullableLong(row, "IdPozycjiPlanu", OPERATION),
              parseDate(requiredText(row, "Dzien", OPERATION)),
              requiredLong(row, "IdPoraLekcji", OPERATION),
              nullableLong(row, "IdPodzial", OPERATION),
              nullableLong(row, "IdPrzedmiot", OPERATION),
              nullableLong(row, "IdPracownik", OPERATION),
              nullableLong(row, "IdSala", OPERATION),
              annotations(row),
              booleanOrFalse(row, "Bolded", OPERATION),
              booleanOrFalse(row, "Striked", OPERATION)));
    }
    return mapped;
  }

  private static EnumSet<ChangeSignal> changeSignals(BaseRow base, EffectiveRow effective) {
    EnumSet<ChangeSignal> signals = EnumSet.noneOf(ChangeSignal.class);
    if (base == null) {
      signals.add(ChangeSignal.UNMATCHED_ENTRY);
    } else {
      if (base.changeMarked()) {
        signals.add(ChangeSignal.BASE_MARKER);
      }
      if (!base.hasSameLessonValues(effective)) {
        signals.add(ChangeSignal.FIELD_DIFFERENCE);
      }
    }
    if (effective.bolded()) {
      signals.add(ChangeSignal.BOLDED);
    }
    if (effective.striked()) {
      signals.add(ChangeSignal.STRIKED);
    }
    return signals;
  }

  private static LessonChangeContext changeContext(BaseRow base, EffectiveRow effective) {
    LessonOccurrence effectiveOccurrence = effective.occurrence();
    if (base == null) {
      return LessonChangeContext.effectiveOnly(effectiveOccurrence);
    }
    if (!base.date().equals(effective.date())
        || base.lessonPeriodId() != effective.lessonPeriodId()) {
      throw new VulcanProtocolException(OPERATION);
    }
    return LessonChangeContext.matched(base.occurrence(), effectiveOccurrence);
  }

  private static List<String> annotations(JsonNode row) {
    JsonNode annotations = row.get("ChangeAnnotation");
    if (annotations == null || annotations.isNull()) {
      return List.of();
    }
    if (!annotations.isArray()) {
      throw new VulcanProtocolException(OPERATION);
    }
    List<String> values = new ArrayList<>();
    for (JsonNode annotation : annotations) {
      if (!annotation.isString()) {
        throw new VulcanProtocolException(OPERATION);
      }
      String value = annotation.stringValue().trim();
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return List.copyOf(values);
  }

  private static String formatDate(LocalDate date) {
    return date.atStartOfDay().format(REQUEST_TIMESTAMP);
  }

  private static LocalDate parseDate(String value) {
    if (value.length() < 10) {
      throw new VulcanProtocolException(OPERATION);
    }
    try {
      return LocalDate.parse(value.substring(0, 10));
    } catch (RuntimeException exception) {
      throw new VulcanProtocolException(OPERATION);
    }
  }

  private record BaseRow(
      long rowId,
      LocalDate date,
      long lessonPeriodId,
      Long groupId,
      Long subjectId,
      Long teacherId,
      Long roomId,
      boolean changeMarked) {

    private LessonOccurrence occurrence() {
      return new LessonOccurrence(date, lessonPeriodId, subjectId, teacherId, roomId, groupId);
    }

    private boolean hasSameLessonValues(EffectiveRow effective) {
      return date.equals(effective.date())
          && lessonPeriodId == effective.lessonPeriodId()
          && Objects.equals(groupId, effective.groupId())
          && Objects.equals(subjectId, effective.subjectId())
          && Objects.equals(teacherId, effective.teacherId())
          && Objects.equals(roomId, effective.roomId());
    }
  }

  private record EffectiveRow(
      Long baseRowId,
      LocalDate date,
      long lessonPeriodId,
      Long groupId,
      Long subjectId,
      Long teacherId,
      Long roomId,
      List<String> annotations,
      boolean bolded,
      boolean striked) {

    private LessonOccurrence occurrence() {
      return new LessonOccurrence(date, lessonPeriodId, subjectId, teacherId, roomId, groupId);
    }
  }
}
