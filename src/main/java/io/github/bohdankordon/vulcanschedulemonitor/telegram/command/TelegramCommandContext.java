package io.github.bohdankordon.vulcanschedulemonitor.telegram.command;

public record TelegramCommandContext(long appUserId, long privateChatId) {}
