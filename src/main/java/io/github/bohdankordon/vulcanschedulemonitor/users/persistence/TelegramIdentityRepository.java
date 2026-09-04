package io.github.bohdankordon.vulcanschedulemonitor.users.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface TelegramIdentityRepository extends JpaRepository<TelegramIdentityEntity, Long> {

  Optional<TelegramIdentityEntity> findByTelegramUserId(long telegramUserId);
}
