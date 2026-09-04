package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface VulcanClassCatalogRepository extends JpaRepository<VulcanClassCatalogEntity, Long> {

  List<VulcanClassCatalogEntity> findAllByVulcanAccountId(long accountId);

  List<VulcanClassCatalogEntity> findAllByVulcanAccountIdAndActiveTrueOrderByNameAscJournalIdAsc(
      long accountId);

  Optional<VulcanClassCatalogEntity> findByVulcanAccountIdAndJournalId(
      long accountId, long journalId);

  Optional<VulcanClassCatalogEntity> findByIdAndVulcanAccountIdAndActiveTrue(
      long id, long accountId);

  Optional<VulcanClassCatalogEntity> findByIdAndVulcanAccountId(long id, long accountId);

  long countByVulcanAccountIdAndActiveTrue(long accountId);
}
