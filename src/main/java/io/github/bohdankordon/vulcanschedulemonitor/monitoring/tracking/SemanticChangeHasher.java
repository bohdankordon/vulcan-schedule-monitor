package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.LessonChangeContext;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.TeacherSubstitution;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.UnknownScheduleChange;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.LessonOccurrence;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;

public final class SemanticChangeHasher {

  private static final String KEY_VERSION = "change-key-v1";
  private static final String FINGERPRINT_VERSION = "change-fingerprint-v1";

  public String changeKey(long journalId, ScheduleChange change) {
    Objects.requireNonNull(change, "change must not be null");
    ChangeType type = ChangeType.from(change);
    LessonOccurrence identity =
        change.context().planned().orElseGet(() -> change.context().effective().orElseThrow());

    CanonicalEncoder encoder = new CanonicalEncoder();
    encoder.text("version", KEY_VERSION);
    encoder.number("journal-id", journalId);
    encoder.text("change-type", type.name());
    encoder.occurrence("identity-occurrence", identity);
    return encoder.sha256();
  }

  public String fingerprint(ScheduleChange change) {
    Objects.requireNonNull(change, "change must not be null");
    ChangeType type = ChangeType.from(change);

    CanonicalEncoder encoder = new CanonicalEncoder();
    encoder.text("version", FINGERPRINT_VERSION);
    encoder.text("change-type", type.name());
    encoder.context(change.context());

    if (change instanceof TeacherSubstitution substitution) {
      encoder.text("replacement-teacher-code", substitution.replacementTeacherCode());
      encoder.text("replacement-subject-code", substitution.replacementSubjectCode());
    } else if (change instanceof UnknownScheduleChange unknown) {
      var signals = unknown.signals().stream().sorted(Comparator.comparing(Enum::name)).toList();
      encoder.number("signal-count", signals.size());
      for (int index = 0; index < signals.size(); index++) {
        encoder.text("signal-" + index, signals.get(index).name());
      }
      encoder.number("annotation-count", unknown.unparsedAnnotations().size());
      for (int index = 0; index < unknown.unparsedAnnotations().size(); index++) {
        encoder.text("annotation-" + index, unknown.unparsedAnnotations().get(index));
      }
    }
    return encoder.sha256();
  }

  HashedScheduleChange hash(long journalId, ScheduleChange change) {
    LessonOccurrence identity =
        change.context().planned().orElseGet(() -> change.context().effective().orElseThrow());
    return new HashedScheduleChange(
        changeKey(journalId, change),
        fingerprint(change),
        new ChangeMetadata(
            ChangeType.from(change),
            identity.date(),
            identity.lessonPeriodId(),
            identity.groupId(),
            identity.subjectId()),
        change);
  }

  private static final class CanonicalEncoder {

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final DataOutputStream output = new DataOutputStream(bytes);

    void context(LessonChangeContext context) {
      occurrence("planned", context.planned().orElse(null));
      occurrence("effective", context.effective().orElse(null));
    }

    void occurrence(String label, LessonOccurrence occurrence) {
      if (occurrence == null) {
        nullValue(label);
        return;
      }
      marker(label, true);
      text(label + ".date", occurrence.date().toString());
      number(label + ".lesson-period-id", occurrence.lessonPeriodId());
      nullableNumber(label + ".subject-id", occurrence.subjectId());
      nullableNumber(label + ".teacher-id", occurrence.teacherId());
      nullableNumber(label + ".room-id", occurrence.roomId());
      nullableNumber(label + ".group-id", occurrence.groupId());
    }

    void text(String label, String value) {
      Objects.requireNonNull(value, "canonical value must not be null");
      marker(label, true);
      writeBytes(Normalizer.normalize(value, Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8));
    }

    void number(String label, long value) {
      text(label, Long.toString(value));
    }

    void nullableNumber(String label, Long value) {
      if (value == null) {
        nullValue(label);
      } else {
        number(label, value);
      }
    }

    String sha256() {
      try {
        return HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
      } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException("SHA-256 is unavailable", exception);
      }
    }

    private void nullValue(String label) {
      marker(label, false);
    }

    private void marker(String label, boolean present) {
      writeBytes(label.getBytes(StandardCharsets.UTF_8));
      try {
        output.writeBoolean(present);
      } catch (IOException exception) {
        throw new IllegalStateException("Unable to encode semantic change", exception);
      }
    }

    private void writeBytes(byte[] value) {
      try {
        output.writeInt(value.length);
        output.write(value);
      } catch (IOException exception) {
        throw new IllegalStateException("Unable to encode semantic change", exception);
      }
    }
  }
}
