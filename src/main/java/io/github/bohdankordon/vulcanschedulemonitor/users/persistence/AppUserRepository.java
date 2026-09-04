package io.github.bohdankordon.vulcanschedulemonitor.users.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {}
