package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.token;

public interface VulcanConnectLinkService {

  ConnectLink issue(long appUserId);
}
