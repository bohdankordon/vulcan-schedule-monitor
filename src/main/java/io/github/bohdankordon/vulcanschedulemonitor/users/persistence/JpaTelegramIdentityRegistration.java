package io.github.bohdankordon.vulcanschedulemonitor.users.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.users.ApplicationUser;
import io.github.bohdankordon.vulcanschedulemonitor.users.TelegramIdentityRegistration;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaTelegramIdentityRegistration implements TelegramIdentityRegistration {

  private final AppUserRepository userRepository;
  private final TelegramIdentityRepository identityRepository;
  private final Clock clock;

  JpaTelegramIdentityRegistration(
      AppUserRepository userRepository,
      TelegramIdentityRepository identityRepository,
      Clock clock) {
    this.userRepository = userRepository;
    this.identityRepository = identityRepository;
    this.clock = clock;
  }

  @Override
  @Transactional
  public ApplicationUser registerOrUpdate(long telegramUserId, long privateChatId) {
    Instant now = clock.instant();
    return identityRepository
        .findByTelegramUserId(telegramUserId)
        .map(identity -> updateExisting(identity, privateChatId, now))
        .orElseGet(() -> createUserAndIdentity(telegramUserId, privateChatId, now));
  }

  private ApplicationUser updateExisting(
      TelegramIdentityEntity identity, long privateChatId, Instant now) {
    AppUserEntity user =
        userRepository
            .findById(identity.appUserId())
            .orElseThrow(
                () -> new IllegalStateException("Telegram identity has no application user"));
    user.reactivate(now);
    identity.updatePrivateChatId(privateChatId, now);
    return toModel(user);
  }

  private ApplicationUser createUserAndIdentity(
      long telegramUserId, long privateChatId, Instant now) {
    AppUserEntity user = userRepository.save(new AppUserEntity(now));
    identityRepository.save(
        new TelegramIdentityEntity(user.id(), telegramUserId, privateChatId, now));
    return toModel(user);
  }

  private static ApplicationUser toModel(AppUserEntity entity) {
    Objects.requireNonNull(entity.id(), "Persisted application user id must not be null");
    return new ApplicationUser(
        entity.id(), entity.active(), entity.createdAt(), entity.updatedAt());
  }
}
