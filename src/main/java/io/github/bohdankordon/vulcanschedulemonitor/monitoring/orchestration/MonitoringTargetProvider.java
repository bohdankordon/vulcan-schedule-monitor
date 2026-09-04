package io.github.bohdankordon.vulcanschedulemonitor.monitoring.orchestration;

import java.util.Collection;

@FunctionalInterface
public interface MonitoringTargetProvider {

  Collection<MonitoringTarget> activeTargets();
}
