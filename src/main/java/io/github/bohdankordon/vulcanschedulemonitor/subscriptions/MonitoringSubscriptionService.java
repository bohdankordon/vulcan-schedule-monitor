package io.github.bohdankordon.vulcanschedulemonitor.subscriptions;

import java.util.List;

public interface MonitoringSubscriptionService {

  MonitoringSubscription enable(long appUserId, long journalId);

  void disable(long appUserId, long journalId);

  List<Long> activeJournalIds(long appUserId);

  boolean isSubscribed(long appUserId, long journalId);
}
