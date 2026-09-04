package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection;

public interface VulcanConnectionStatusService {

  VulcanConnectionStatus statusForUser(long appUserId);
}
