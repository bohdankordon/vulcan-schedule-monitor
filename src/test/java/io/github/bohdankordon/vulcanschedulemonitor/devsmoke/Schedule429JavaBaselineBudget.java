package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

/**
 * Independent policy: Chromium may never spend a schedule request; Java has one nonrenewable
 * permit.
 */
public final class Schedule429JavaBaselineBudget {
  private boolean unexpectedBrowser;
  private int javaRequests;

  public synchronized void unexpectedBrowserSchedule() {
    unexpectedBrowser = true;
  }

  public synchronized boolean unexpectedBrowser() {
    return unexpectedBrowser;
  }

  public synchronized void requireQuietBrowser() {
    if (unexpectedBrowser)
      throw new Schedule429Failure(Schedule429Failure.Category.SECURITY_INVARIANT);
  }

  public synchronized void takeJavaPermit() {
    requireQuietBrowser();
    if (javaRequests != 0)
      throw new Schedule429Failure(Schedule429Failure.Category.INTERNAL_INVARIANT);
    javaRequests++;
  }

  public synchronized int javaRequests() {
    return javaRequests;
  }
}
