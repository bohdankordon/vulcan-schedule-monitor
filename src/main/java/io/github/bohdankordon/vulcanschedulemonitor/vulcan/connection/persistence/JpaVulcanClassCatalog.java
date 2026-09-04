package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.CatalogClass;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog.VulcanClassCatalog;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaVulcanClassCatalog implements VulcanClassCatalog {

  private final VulcanAccountRepository accounts;
  private final VulcanClassCatalogRepository catalog;

  JpaVulcanClassCatalog(VulcanAccountRepository accounts, VulcanClassCatalogRepository catalog) {
    this.accounts = accounts;
    this.catalog = catalog;
  }

  @Override
  @Transactional(readOnly = true)
  public List<CatalogClass> listActiveForUser(long appUserId) {
    return accounts
        .findByAppUserId(appUserId)
        .map(VulcanAccountEntity::id)
        .map(catalog::findAllByVulcanAccountIdAndActiveTrueOrderByNameAscJournalIdAsc)
        .orElseGet(List::of)
        .stream()
        .map(JpaVulcanClassCatalog::toModel)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<CatalogClass> findActiveForUser(long appUserId, long catalogId) {
    return accounts
        .findByAppUserId(appUserId)
        .flatMap(
            account -> catalog.findByIdAndVulcanAccountIdAndActiveTrue(catalogId, account.id()))
        .map(JpaVulcanClassCatalog::toModel);
  }

  private static CatalogClass toModel(VulcanClassCatalogEntity entity) {
    return new CatalogClass(
        entity.id(),
        entity.journalId(),
        entity.classId(),
        entity.name(),
        entity.schoolUnit(),
        entity.grade(),
        entity.schoolYear(),
        entity.validFrom(),
        entity.validTo());
  }
}
