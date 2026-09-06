package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.MonitoringTarget;
import java.util.function.BiConsumer;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared strict, read-only ownership resolution. Identifiers never enter diagnostic output. */
final class PersistedDiagnosticTarget {
  static MonitoringTarget resolve(JdbcTemplate jdbc, BiConsumer<String, Object> report) {
    var accounts =
        jdbc.query(
            "SELECT a.id,a.status FROM vulcan_account a JOIN app_user u ON u.id=a.app_user_id WHERE u.active ORDER BY a.id LIMIT 2",
            (rs, row) -> new Account(rs.getLong(1), rs.getString(2)));
    if (accounts.size() != 1) {
      report.accept("category", accounts.isEmpty() ? "NO_ACCOUNT" : "AMBIGUOUS_ACCOUNT");
      return null;
    }
    var account = accounts.getFirst();
    report.accept("account.connected", account.status.equals("CONNECTED"));
    report.accept("account.reconnectRequired", account.status.equals("RECONNECT_REQUIRED"));
    if (!account.status.equals("CONNECTED")) {
      report.accept("category", "ACCOUNT_NOT_CONNECTED");
      return null;
    }
    var targets =
        jdbc.query(
            """
        SELECT a.id,c.id,c.journal_id FROM monitoring_subscription s
        JOIN app_user u ON u.id=s.app_user_id
        JOIN vulcan_class_catalog c ON c.id=s.catalog_class_id
        JOIN vulcan_account a ON a.id=c.vulcan_account_id AND a.app_user_id=s.app_user_id
        WHERE s.enabled AND u.active AND c.active AND a.status='CONNECTED' AND a.id=?
        ORDER BY c.id LIMIT 2
        """,
            (rs, row) -> new MonitoringTarget(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
            account.id);
    if (targets.size() != 1) {
      report.accept("category", targets.isEmpty() ? "NO_TARGET" : "AMBIGUOUS_TARGET");
      return null;
    }
    report.accept("targetResolved", true);
    return targets.getFirst();
  }

  private record Account(long id, String status) {}
}
