package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.catalog;

import java.util.List;
import java.util.Optional;

public interface VulcanClassCatalog {

  List<CatalogClass> listActiveForUser(long appUserId);

  Optional<CatalogClass> findActiveForUser(long appUserId, long catalogId);

  Optional<CatalogClass> findForUser(long appUserId, long catalogId);
}
