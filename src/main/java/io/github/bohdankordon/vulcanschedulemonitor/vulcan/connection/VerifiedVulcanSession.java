package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.journal.SchoolClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.VulcanSessionMaterial;
import java.util.List;
import java.util.Objects;

/** A successfully verified session snapshot and its complete authorized class discovery. */
public final class VerifiedVulcanSession {

  private final VulcanSessionMaterial sessionMaterial;
  private final List<SchoolClass> classes;

  public VerifiedVulcanSession(VulcanSessionMaterial sessionMaterial, List<SchoolClass> classes) {
    this.sessionMaterial =
        Objects.requireNonNull(sessionMaterial, "sessionMaterial must not be null");
    this.classes = List.copyOf(Objects.requireNonNull(classes, "classes must not be null"));
  }

  public VulcanSessionMaterial sessionMaterial() {
    return sessionMaterial;
  }

  public List<SchoolClass> classes() {
    return classes;
  }

  @Override
  public String toString() {
    return "VerifiedVulcanSession[session=[redacted], classes=[redacted]]";
  }
}
