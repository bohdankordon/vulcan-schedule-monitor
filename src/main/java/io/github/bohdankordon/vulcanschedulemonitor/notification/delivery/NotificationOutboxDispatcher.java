package io.github.bohdankordon.vulcanschedulemonitor.notification.delivery;

import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxClaim;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxFailureCategory;
import io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.NotificationOutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class NotificationOutboxDispatcher {

  private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);

  private final NotificationOutboxStore store;
  private final NotificationDeliveryGateway gateway;
  private final Clock clock;
  private final NotificationDispatchPolicy policy;

  public NotificationOutboxDispatcher(
      NotificationOutboxStore store, NotificationDeliveryGateway gateway, Clock clock) {
    this(store, gateway, clock, NotificationDispatchPolicy.defaults());
  }

  public NotificationOutboxDispatcher(
      NotificationOutboxStore store,
      NotificationDeliveryGateway gateway,
      Clock clock,
      NotificationDispatchPolicy policy) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.gateway = Objects.requireNonNull(gateway, "gateway must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
  }

  public NotificationDispatchSummary dispatchOnce() {
    Instant claimTime = clock.instant();
    var claims = store.claimDue(claimTime, policy.batchSize(), policy.leaseDuration());
    int delivered = 0;
    int retried = 0;
    int dead = 0;

    for (NotificationOutboxClaim claim : claims) {
      if (claim.message().attemptNumber() > policy.maxAttempts()) {
        if (store.markDead(
            claim.message().id(),
            claim.ownershipToken(),
            NotificationOutboxFailureCategory.EXHAUSTED)) {
          dead++;
        }
        continue;
      }
      try {
        gateway.deliver(claim.message());
      } catch (NotificationDeliveryException exception) {
        if (exception.kind() == DeliveryFailureKind.PERMANENT) {
          if (store.markDead(
              claim.message().id(),
              claim.ownershipToken(),
              NotificationOutboxFailureCategory.PERMANENT)) {
            dead++;
          }
        } else if (claim.message().attemptNumber() >= policy.maxAttempts()) {
          if (store.markDead(
              claim.message().id(),
              claim.ownershipToken(),
              NotificationOutboxFailureCategory.RETRYABLE)) {
            dead++;
          }
        } else {
          Instant failedAt = clock.instant();
          Instant nextAttemptAt = failedAt.plus(retryDelay(claim.message().attemptNumber()));
          if (exception.retryAfter().isPresent()) {
            Instant providerNotBefore = failedAt.plus(exception.retryAfter().orElseThrow());
            if (providerNotBefore.isAfter(nextAttemptAt)) {
              nextAttemptAt = providerNotBefore;
            }
          }
          if (store.scheduleRetry(
              claim.message().id(),
              claim.ownershipToken(),
              nextAttemptAt,
              NotificationOutboxFailureCategory.RETRYABLE)) {
            retried++;
          }
        }
        continue;
      } catch (RuntimeException exception) {
        if (claim.message().attemptNumber() >= policy.maxAttempts()) {
          if (store.markDead(
              claim.message().id(),
              claim.ownershipToken(),
              NotificationOutboxFailureCategory.UNEXPECTED)) {
            dead++;
          }
        } else {
          Instant nextAttemptAt = clock.instant().plus(retryDelay(claim.message().attemptNumber()));
          if (store.scheduleRetry(
              claim.message().id(),
              claim.ownershipToken(),
              nextAttemptAt,
              NotificationOutboxFailureCategory.UNEXPECTED)) {
            retried++;
          }
        }
        continue;
      }
      if (store.markDelivered(claim.message().id(), claim.ownershipToken(), clock.instant())) {
        delivered++;
      }
    }

    return new NotificationDispatchSummary(claims.size(), delivered, retried, dead);
  }

  private Duration retryDelay(int attemptNumber) {
    long seconds = INITIAL_RETRY_DELAY.toSeconds();
    long maximumSeconds = policy.maximumRetryDelay().toSeconds();
    for (int attempt = 1; attempt < attemptNumber && seconds < maximumSeconds; attempt++) {
      seconds = Math.min(maximumSeconds, seconds * 3);
    }
    return Duration.ofSeconds(seconds);
  }
}
