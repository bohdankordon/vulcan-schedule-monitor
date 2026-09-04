package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VulcanConnectTokenRepository extends JpaRepository<VulcanConnectTokenEntity, Long> {

  Optional<VulcanConnectTokenEntity> findByTokenHash(byte[] tokenHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select token from VulcanConnectTokenEntity token where token.tokenHash = :tokenHash")
  Optional<VulcanConnectTokenEntity> findLockedByTokenHash(@Param("tokenHash") byte[] tokenHash);
}
