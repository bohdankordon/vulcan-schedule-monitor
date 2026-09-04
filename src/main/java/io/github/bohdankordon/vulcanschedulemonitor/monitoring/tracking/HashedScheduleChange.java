package io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking;

import io.github.bohdankordon.vulcanschedulemonitor.schedule.change.ScheduleChange;

record HashedScheduleChange(
    String changeKey, String fingerprint, ChangeMetadata metadata, ScheduleChange change) {}
