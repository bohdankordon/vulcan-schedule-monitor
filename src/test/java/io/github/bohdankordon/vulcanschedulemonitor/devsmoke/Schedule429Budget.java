package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

/** Shared by the browser route guard and orchestrator. Failed attempts never release a permit. */
public final class Schedule429Budget {
  private boolean armed;
  private int browser;
  private int java;
  private int blocked;
  private boolean browserSucceeded;

  public void arm() {
    armed = true;
  }

  public synchronized boolean permitBrowser(boolean safeTarget) {
    if (!armed || !safeTarget || browser != 0) {
      blocked++;
      return false;
    }
    browser++;
    return true;
  }

  public void browserResult(int status, boolean jsonEnvelope) {
    browserSucceeded = browser == 1 && status >= 200 && status < 300 && jsonEnvelope;
  }

  public boolean javaPermitted() {
    return browserSucceeded && java == 0;
  }

  public synchronized void takeJavaPermit() {
    if (!javaPermitted()) throw new IllegalStateException("Java comparison not permitted");
    java++;
  }

  public int browserRequests() {
    return browser;
  }

  public void report(Schedule429Report report) {
    report.put("browserScheduleRequests", browser);
    report.put("javaScheduleRequests", java);
    report.put("blockedExtraScheduleRequests", blocked);
  }
}
