package io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal;

import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.booleanOrFalse;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredInt;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredLong;
import static io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanJson.requiredText;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanHttpTransport;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.http.VulcanProtocolException;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSession;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

public final class VulcanJournalAdapter {

  private static final String OPERATION = "GetTree";
  private static final DateTimeFormatter REQUEST_TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

  private final VulcanSession session;
  private final VulcanHttpTransport transport;
  private final Clock clock;

  public VulcanJournalAdapter(VulcanSession session, VulcanHttpTransport transport, Clock clock) {
    this.session = Objects.requireNonNull(session, "session must not be null");
    this.transport = Objects.requireNonNull(transport, "transport must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public List<SchoolClass> getTree(int schoolYear) {
    LocalDateTime requestedAt = LocalDateTime.now(clock);
    URI uri =
        UriComponentsBuilder.fromUri(session.resolve("Dziennik.mvc/GetTree"))
            .queryParam("_dc", clock.millis())
            .queryParam("zadanaData", requestedAt.format(REQUEST_TIMESTAMP))
            .queryParam("rokSzkolny", schoolYear)
            .queryParam("idDziennik", "")
            .queryParam("node", "root")
            .build()
            .encode()
            .toUri();
    return mapResponse(transport.get(OPERATION, uri));
  }

  List<SchoolClass> mapResponse(JsonNode response) {
    Map<JournalKey, SchoolClass> discovered = new LinkedHashMap<>();
    if (response == null || !response.isObject()) {
      throw new VulcanProtocolException(OPERATION);
    }
    visit(response, discovered);
    return List.copyOf(discovered.values());
  }

  private static void visit(JsonNode node, Map<JournalKey, SchoolClass> discovered) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isObject()) {
      JsonNode objectData = node.get("ObjectData");
      if (objectData != null && objectData.isObject()) {
        addJournal(objectData, discovered);
      }
      node.properties().forEach(entry -> visit(entry.getValue(), discovered));
      return;
    }
    if (node.isArray()) {
      node.forEach(child -> visit(child, discovered));
    }
  }

  private static void addJournal(JsonNode data, Map<JournalKey, SchoolClass> discovered) {
    if (!booleanOrFalse(data, "IstniejeDziennik", OPERATION)) {
      return;
    }
    long journalId = requiredLong(data, "IdDziennik", OPERATION);
    long classId = requiredLong(data, "IdOddzial", OPERATION);
    SchoolClass schoolClass =
        new SchoolClass(
            journalId,
            classId,
            requiredText(data, "NazwaOddzialu", OPERATION),
            requiredText(data, "SkrotJednostki", OPERATION),
            requiredInt(data, "PoziomOddzialu", OPERATION),
            requiredInt(data, "RokSzkolny", OPERATION),
            parseDate(requiredText(data, "DziennikDataOd", OPERATION)),
            parseDate(requiredText(data, "DziennikDataDo", OPERATION)));
    discovered.putIfAbsent(new JournalKey(journalId, classId), schoolClass);
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

  private record JournalKey(long journalId, long classId) {}
}
