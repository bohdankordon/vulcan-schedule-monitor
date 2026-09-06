package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration.*;
import io.github.bohdankordon.vulcanschedulemonitor.monitoring.tracking.*;
import io.github.bohdankordon.vulcanschedulemonitor.schedule.model.ScheduleSnapshot;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.connection.*;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.schedule.PersistedAccountWeeklyScheduleSource;
import io.github.bohdankordon.vulcanschedulemonitor.vulcan.session.*;
import jakarta.persistence.EntityManager;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Diagnostic-only production cycle with isolated tracking and unconditional database rollback. */
public final class VulcanPersistedMonitoringSequence {
  public static void main(String[] args) {
    PrintStream output = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream()));
    System.setErr(new PrintStream(OutputStream.nullOutputStream()));
    var report = new MonitoringSequenceReport();
    try {
      if (args.length == 1 && args[0].equals("--authorized-persisted-monitoring-sequence")) {
        report.put("category", "APP_RUNNING");
        try (var guard = VulcanPersistedSessionJavaBaseline.reserveApplicationPort(8080)) {
          report.put("category", "KEY_UNAVAILABLE");
          byte[] bytes = System.in.readNBytes(129);
          String key;
          try {
            if (bytes.length != 44) throw new IllegalArgumentException();
            key = new String(bytes, StandardCharsets.US_ASCII);
            byte[] decoded = Base64.getDecoder().decode(key);
            try {
              if (decoded.length != 32) throw new IllegalArgumentException();
            } finally {
              Arrays.fill(decoded, (byte) 0);
            }
          } finally {
            Arrays.fill(bytes, (byte) 0);
          }
          report.put("category", "DATABASE_UNAVAILABLE");
          try (var context =
              VulcanPersistedSessionJavaBaseline.open(
                  "jdbc:postgresql://localhost:54329/vulcan_monitor",
                  "vulcan",
                  "vulcan-local-dev-only",
                  key,
                  false)) {
            execute(
                context,
                context.getBean(VulcanSessionManager.class),
                new SequenceDispatchBudget(),
                report,
                new PortalUrlValidator()::isAllowedRuntimeUri,
                context.getBean(Clock.class),
                new ThreadDelayStrategy());
          }
        }
      }
    } catch (Throwable ignored) {
      /* No application/secret exception leaves the child. */
    }
    output.println(report.json());
    System.exit("SUCCESS".equals(report.facts().get("result")) ? 0 : 1);
  }

  static void execute(
      ConfigurableApplicationContext context,
      VulcanSessionManager sessions,
      SequenceDispatchBudget budget,
      MonitoringSequenceReport report,
      Predicate<URI> allowed,
      Clock clock,
      DelayStrategy delay) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      report.put("category", "TRANSACTION_REQUIRED");
      return;
    }
    var original = new AtomicReference<VulcanSessionMaterial>();
    var selected = new AtomicReference<MonitoringTarget>();
    var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    try {
      report.put("category", "DATABASE_UNAVAILABLE");
      tx.executeWithoutResult(
          status -> {
            // Mark BEFORE any load/request/write; exceptions, early returns and success all roll
            // back.
            status.setRollbackOnly();
            var target =
                PersistedDiagnosticTarget.resolve(context.getBean(JdbcTemplate.class), report::put);
            if (target == null) return;
            selected.set(target);
            report.put("category", "SESSION_UNAVAILABLE");
            original.set(sessions.loadCurrent(target.vulcanAccountId()).snapshotMaterial());
            report.put("persistedSessionLoaded", true);
            report.put("category", "UNSAFE_SESSION");
            if (!allowed.test(
                original.get().applicationBaseUri().resolve("PlanLekcji.mvc/GetPlanLekcjiContext")))
              return;
            // Pin the planning instant only, so validation and runner cannot straddle a week
            // boundary.
            var planner =
                new MonitoringScopePlanner(
                    Clock.fixed(clock.instant(), MonitoringScopePlanner.WARSAW));
            var scopes = planner.plan(List.of(target));
            report.put("category", "INVALID_PLAN");
            if (scopes.size() != 2
                || !scopes.get(1).weekStart().equals(scopes.get(0).weekStart().plusWeeks(1)))
              return;
            report.put("scopeCount", 2);
            report.put("category", "HARNESS_FAILURE");
            new Cycle(
                    context, sessions, budget, report, allowed, clock, delay, target, planner,
                    scopes)
                .run();
          });
    } catch (Throwable ignored) {
      /* Rollback has been requested even if a source/persistence failed. */
    } finally {
      report.put("totalScheduleRequests", budget.count());
      if (original.get() != null && selected.get() != null) {
        try {
          // New transaction/context after rollback, production decrypt/load path again.
          var check = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
          check.setReadOnly(true);
          boolean restored =
              Boolean.TRUE.equals(
                  check.execute(
                      status ->
                          sameMaterial(
                              original.get(),
                              sessions
                                  .loadCurrent(selected.get().vulcanAccountId())
                                  .snapshotMaterial())));
          report.put("databaseSessionRestoredAfterRollback", restored);
          if (!restored) {
            report.put("category", "ROLLBACK_VERIFICATION_FAILED");
            report.put("result", "FAIL");
          }
        } catch (Throwable ignored) {
          report.put("databaseSessionRestoredAfterRollback", "UNAVAILABLE");
          report.put("category", "ROLLBACK_VERIFICATION_FAILED");
          report.put("result", "FAIL");
        }
      }
    }
  }

  static boolean sameMaterial(VulcanSessionMaterial a, VulcanSessionMaterial b) {
    return a.applicationBaseUri().equals(b.applicationBaseUri())
        && a.refererUri().equals(b.refererUri())
        && a.requestVerificationToken().equals(b.requestVerificationToken())
        && a.appGuid().equals(b.appGuid())
        && pairs(a.cookieHeader()).equals(pairs(b.cookieHeader()));
  }

  private static List<String> pairs(String header) {
    return Arrays.stream(header.split(";", -1)).map(String::trim).sorted().toList();
  }

  private static final class Cycle {
    final ConfigurableApplicationContext context;
    final VulcanSessionManager sessions;
    final SequenceDispatchBudget budget;
    final MonitoringSequenceReport report;
    final Predicate<URI> allowed;
    final Clock clock;
    final DelayStrategy delay;
    final MonitoringTarget target;
    final MonitoringScopePlanner planner;
    final List<TrackingScope> scopes;
    final int[] attempts = new int[2];
    VulcanSessionMaterial currentPost;
    VulcanSession currentInstance;
    int retries;
    boolean harnessFailure;

    Cycle(
        ConfigurableApplicationContext context,
        VulcanSessionManager sessions,
        SequenceDispatchBudget budget,
        MonitoringSequenceReport report,
        Predicate<URI> allowed,
        Clock clock,
        DelayStrategy delay,
        MonitoringTarget target,
        MonitoringScopePlanner planner,
        List<TrackingScope> scopes) {
      this.context = context;
      this.sessions = sessions;
      this.budget = budget;
      this.report = report;
      this.allowed = allowed;
      this.clock = clock;
      this.delay = delay;
      this.target = target;
      this.planner = planner;
      this.scopes = scopes;
    }

    void run() {
      var properties = new MonitoringProperties();
      if (!properties.getRequestSpacing().equals(Duration.ofMillis(500))
          || !properties.getFallbackRateLimitDelay().equals(Duration.ofSeconds(30))
          || !properties.getMaximumInlineRateLimitDelay().equals(Duration.ofSeconds(10)))
        throw new IllegalStateException("INVALID_POLICY");
      var gate = new RateLimitBackoffGate(clock);
      report.put("gateInitiallyClear", gate.activeUntil(target.vulcanAccountId()).isEmpty());
      var persisted = new PersistedAccountWeeklyScheduleSource(sessions, this::fetch);
      WeeklyScheduleSource observing =
          scope -> {
            var snapshot = persisted.fetchCompleteWeeklySnapshot(scope);
            // Force the encrypted update into PostgreSQL inside the rollback-only transaction.
            var em = context.getBean(EntityManager.class);
            em.flush();
            em.clear();
            if (scope.equals(scopes.getFirst())) {
              boolean saved =
                  currentPost != null
                      && sameMaterial(
                          currentPost,
                          sessions.loadCurrent(target.vulcanAccountId()).snapshotMaterial());
              report.put("current.sessionPersistedAfterSuccess", saved);
              if (!saved) throw ScheduleSourceException.of(SourceFailureKind.INTERRUPTED);
              em.clear(); // NEXT must decrypt from a fresh repository read, not a cached entity.
            }
            return snapshot;
          };
      var resilient =
          new ResilientWeeklyScheduleSource(
              observing,
              delay,
              gate,
              properties.getMaxAttempts(),
              properties.getInitialRetryBackoff(),
              properties.getFallbackRateLimitDelay(),
              properties.getMaximumInlineRateLimitDelay());
      WeeklyScheduleSource source =
          scope -> {
            try {
              return resilient.fetchCompleteWeeklySnapshot(scope);
            } catch (SequenceDispatchBudget.Exhausted ignored) {
              throw ScheduleSourceException.of(SourceFailureKind.INTERRUPTED);
            } catch (ScheduleSourceException failure) {
              throw failure;
            } catch (RuntimeException ignored) {
              harnessFailure = true;
              throw ScheduleSourceException.of(SourceFailureKind.INTERRUPTED);
            } finally {
              if (scope.equals(scopes.getFirst()))
                report.put(
                    "accountBlockedAfterCurrent",
                    gate.activeUntil(target.vulcanAccountId()).isPresent());
            }
          };
      var memory =
          new ActiveChangeStore() {
            final Map<TrackingScope, TrackingState> states = new HashMap<>();

            public TrackingState lockOrCreate(TrackingScope scope) {
              return states.getOrDefault(scope, new TrackingState(scope, false, null, List.of()));
            }

            public void save(TrackingState state) {
              states.put(state.scope(), state);
            }
          };
      var tracker =
          new ScheduleChangeTracker(
              memory, new SemanticChangeHasher(), clock, (scope, result, time) -> {});
      var coordinator = new ScheduleRefreshCoordinator(source, tracker);
      var runner =
          new MonitoringCycleRunner(
              () -> List.of(target),
              planner,
              coordinator,
              duration -> {
                delay.delay(duration);
                report.put("spacingAppliedBeforeNext", true);
              },
              properties.getRequestSpacing(),
              clock);
      var summary = runner.runCycle();
      for (var outcome : summary.outcomes()) {
        String prefix = outcome.scope().equals(scopes.getFirst()) ? "current" : "next";
        report.put(prefix + ".outcome", outcome.category().name());
      }
      boolean blocked =
          summary.outcomes().stream()
              .filter(o -> o.scope().equals(scopes.getFirst()))
              .anyMatch(
                  o ->
                      Set.of(
                              MonitoringOutcomeCategory.AUTHENTICATION_REQUIRED,
                              MonitoringOutcomeCategory.DEFERRED_RATE_LIMIT,
                              MonitoringOutcomeCategory.TRANSIENT_RECOVERY_FAILURE)
                          .contains(o.category()));
      report.put("accountBlockedAfterCurrent", blocked);
      report.put(
          "next.disposition",
          attempts[1] > 0
              ? "DISPATCHED"
              : budget.exhausted()
                  ? "SKIPPED_BUDGET"
                  : blocked
                      ? "SKIPPED_ACCOUNT_BLOCKED"
                      : summary.stoppedEarly() ? "SKIPPED_INTERRUPTED" : "NOT_REACHED");
      report.put(
          "category",
          budget.exhausted()
              ? "BUDGET_EXHAUSTED"
              : harnessFailure ? "HARNESS_FAILURE" : "SEQUENCE_COMPLETED");
      if (budget.exhausted()) {
        String prefix =
            summary.outcomes().getLast().scope().equals(scopes.getFirst()) ? "current" : "next";
        report.put(prefix + ".outcome", "BUDGET_EXHAUSTED");
      }
      if (summary.successCount() == 2 && !budget.exhausted()) report.put("result", "SUCCESS");
    }

    ScheduleSnapshot fetch(VulcanSession session, long journal, LocalDate week) {
      int index = week.equals(scopes.getFirst().weekStart()) ? 0 : 1;
      if (journal != target.journalId() || !week.equals(scopes.get(index).weekStart()))
        throw new IllegalStateException("INVALID_PLAN");
      URI endpoint = session.resolve("PlanLekcji.mvc/GetPlanLekcjiContext");
      if (!allowed.test(endpoint)) throw new IllegalStateException("UNSAFE_REQUEST");
      if (index == 1 && currentPost != null) {
        boolean loaded =
            session != currentInstance && sameMaterial(currentPost, session.snapshotMaterial());
        report.put("next.loadedPostCurrentSession", loaded);
        if (!loaded) throw ScheduleSourceException.of(SourceFailureKind.INTERRUPTED);
      }
      var client = new VulcanClient(session);
      budget.install(client, endpoint);
      int before = budget.count();
      var observation = new NativeSessionBaselineReport();
      var result = new AtomicReference<ScheduleSnapshot>();
      var failure = new AtomicReference<RuntimeException>();
      VulcanNativeSessionJavaBaseline.runOnce(
          new VulcanNativeSessionJavaBaseline.Permit(),
          observation,
          () ->
              NativeSessionCookieObservation.observe(
                  session::snapshotMaterial,
                  observation,
                  () -> {
                    try {
                      result.set(client.getWeekSchedule(journal, week));
                    } catch (RuntimeException exception) {
                      failure.set(exception);
                      throw exception;
                    }
                  }));
      if (budget.count() > before) {
        attempts[index]++;
        if (attempts[index] > 1) retries++;
        report.put("retries", retries);
        report.request(index == 0 ? "CURRENT" : "NEXT", attempts[index], observation);
      }
      if (failure.get() != null) throw failure.get();
      if (result.get() == null) throw new IllegalStateException("HARNESS_FAILURE");
      if (index == 0) {
        currentPost = session.snapshotMaterial();
        currentInstance = session;
      }
      return result.get();
    }
  }
}
