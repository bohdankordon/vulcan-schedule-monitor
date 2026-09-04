package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface VulcanAccountSecretRepository extends JpaRepository<VulcanAccountSecretEntity, Long> {}
