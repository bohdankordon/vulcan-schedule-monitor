package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleChangeTracker {

  private final ActiveChangeStore store;
  private final SemanticChangeHasher hasher;
  private final Clock clock;

  public ScheduleChangeTracker(ActiveChangeStore store, SemanticChangeHasher hasher, Clock clock) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.hasher = Objects.requireNonNull(hasher, "hasher must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Transactional
  public TrackingResult reconcileSuccessfulSnapshot(ScheduleSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "successful snapshot must not be null");
    TrackingScope scope = TrackingScope.from(snapshot);
    Map<String, HashedScheduleChange> current = hashUniqueChanges(snapshot);

    TrackingState previous = store.lockOrCreate(scope);
    Instant now = clock.instant();
    Map<String, ActiveChangeState> previousByKey = new LinkedHashMap<>();
    previous.activeChanges().forEach(state -> previousByKey.put(state.changeKey(), state));

    List<ActiveChangeState> nextActive = new ArrayList<>();
    List<ChangeTransition> transitions = new ArrayList<>();
    for (HashedScheduleChange currentChange : current.values()) {
      ActiveChangeState previousChange = previousByKey.remove(currentChange.changeKey());
      Instant firstSeenAt = previousChange == null ? now : previousChange.firstSeenAt();
      nextActive.add(
          new ActiveChangeState(
              currentChange.changeKey(),
              currentChange.fingerprint(),
              currentChange.metadata(),
              firstSeenAt,
              now));

      if (previous.baselineEstablished()) {
        if (previousChange == null) {
          transitions.add(ChangeTransition.current(ChangeLifecycle.NEW, currentChange));
        } else if (!previousChange.fingerprint().equals(currentChange.fingerprint())) {
          transitions.add(ChangeTransition.current(ChangeLifecycle.UPDATED, currentChange));
        }
      }
    }

    if (previous.baselineEstablished()) {
      previousByKey.values().stream()
          .sorted(java.util.Comparator.comparing(ActiveChangeState::changeKey))
          .map(ChangeTransition::resolved)
          .forEach(transitions::add);
    }

    boolean baselineEstablishedNow = !previous.baselineEstablished();
    store.save(new TrackingState(scope, true, now, nextActive));
    return new TrackingResult(baselineEstablishedNow, nextActive.size(), transitions);
  }

  private Map<String, HashedScheduleChange> hashUniqueChanges(ScheduleSnapshot snapshot) {
    Map<String, HashedScheduleChange> changes = new LinkedHashMap<>();
    for (var change : snapshot.changes()) {
      HashedScheduleChange hashed = hasher.hash(snapshot.journalId(), change);
      if (changes.putIfAbsent(hashed.changeKey(), hashed) != null) {
        throw new DuplicateSemanticChangeKeyException(hashed.changeKey());
      }
    }
    return changes;
  }
}
