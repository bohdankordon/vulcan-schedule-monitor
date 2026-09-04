package io.github.bohdankordon.vulcanschedulemonitor.notification.recipient;

import java.util.List;

public interface NotificationRecipientProvider {

  List<Long> activeRecipientUserIds(long journalId);
}
