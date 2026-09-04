package io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.persistence;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanAccountStatus;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatus;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.VulcanConnectionStatusService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class JpaVulcanConnectionStatusService implements VulcanConnectionStatusService {

  private final VulcanAccountRepository accounts;
  private final VulcanClassCatalogRepository catalog;

  JpaVulcanConnectionStatusService(
      VulcanAccountRepository accounts, VulcanClassCatalogRepository catalog) {
    this.accounts = accounts;
    this.catalog = catalog;
  }

  @Override
  @Transactional(readOnly = true)
  public VulcanConnectionStatus statusForUser(long appUserId) {
    return accounts
        .findByAppUserId(appUserId)
        .map(
            account ->
                new VulcanConnectionStatus(
                    mapStatus(account.status()),
                    Math.toIntExact(catalog.countByVulcanAccountIdAndActiveTrue(account.id()))))
        .orElseGet(() -> new VulcanConnectionStatus(VulcanConnectionStatus.State.NOT_CONNECTED, 0));
  }

  private static VulcanConnectionStatus.State mapStatus(VulcanAccountStatus status) {
    return switch (status) {
      case CONNECTED -> VulcanConnectionStatus.State.CONNECTED;
      case RECONNECT_REQUIRED -> VulcanConnectionStatus.State.RECONNECT_REQUIRED;
      case DISCONNECTED -> VulcanConnectionStatus.State.NOT_CONNECTED;
    };
  }
}
