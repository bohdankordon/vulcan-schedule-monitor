package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VulcanAccountRepository extends JpaRepository<VulcanAccountEntity, Long> {

  Optional<VulcanAccountEntity> findByAppUserId(long appUserId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select account from VulcanAccountEntity account where account.id = :id")
  Optional<VulcanAccountEntity> findLockedById(@Param("id") long id);
}
