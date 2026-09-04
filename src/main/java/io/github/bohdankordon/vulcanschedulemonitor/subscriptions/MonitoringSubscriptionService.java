package io.github.bohdankordon.vulcanschedulemonitor.subscriptions;

import java.util.List;

public interface MonitoringSubscriptionService {

  MonitoringSubscription enable(long appUserId, long catalogClassId);

  void disable(long appUserId, long catalogClassId);

  List<MonitoringSubscription> activeSubscriptions(long appUserId);

  List<MonitoringClassSelection> availableClasses(long appUserId);

  boolean isSubscribed(long appUserId, long catalogClassId);
}
