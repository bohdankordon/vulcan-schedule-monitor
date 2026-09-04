package io.github.bohdankordon.vulcanschedulemonitor.notification.outbox.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntity, Long> {

  @Query(
      value =
          """
          SELECT *
          FROM notification_outbox
          WHERE (status = 'PENDING' AND next_attempt_at <= :now)
             OR (status = 'IN_FLIGHT' AND lease_until <= :now)
          ORDER BY id
          LIMIT :batchSize
          FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<NotificationOutboxEntity> findDueForUpdate(
      @Param("now") Instant now, @Param("batchSize") int batchSize);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT event FROM NotificationOutboxEntity event WHERE event.id = :id")
  Optional<NotificationOutboxEntity> findForUpdate(@Param("id") long id);
}
