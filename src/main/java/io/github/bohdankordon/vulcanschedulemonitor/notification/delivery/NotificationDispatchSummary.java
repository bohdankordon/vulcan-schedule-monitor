package io.github.bohdankordon.vulcanschedulemonitor.notification.delivery;

public record NotificationDispatchSummary(int claimed, int delivered, int retried, int dead) {}
