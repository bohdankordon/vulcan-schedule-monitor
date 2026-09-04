package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.ChangeLifecycle;

public enum NotificationEventType {
  BASELINE_ESTABLISHED,
  CHANGE_NEW,
  CHANGE_UPDATED,
  CHANGE_RESOLVED;

  public static NotificationEventType from(ChangeLifecycle lifecycle) {
    return switch (lifecycle) {
      case NEW -> CHANGE_NEW;
      case UPDATED -> CHANGE_UPDATED;
      case RESOLVED -> CHANGE_RESOLVED;
    };
  }
}
