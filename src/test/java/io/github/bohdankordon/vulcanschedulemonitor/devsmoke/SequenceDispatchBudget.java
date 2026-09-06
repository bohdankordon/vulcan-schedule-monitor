package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import java.net.URI;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** One process-local nonrenewable permit shared across scopes AND resilience retries. */
final class SequenceDispatchBudget {
  private int count;
  private boolean exhausted;

  synchronized int count() {
    return count;
  }

  synchronized boolean exhausted() {
    return exhausted;
  }

  private synchronized void take() {
    if (count == 2) {
      exhausted = true;
      throw new Exhausted();
    }
    count++;
  }

  static final class Exhausted extends RuntimeException {
    Exhausted() {
      super("BUDGET_EXHAUSTED", null, false, false);
    }
  }

  void install(VulcanClient client, URI endpoint) {
    Object adapter = ReflectionTestUtils.getField(client, "scheduleAdapter");
    Object transport = ReflectionTestUtils.getField(adapter, "transport");
    Object rest = ReflectionTestUtils.getField(transport, "restClient");
    Object original = ReflectionTestUtils.getField(rest, "clientRequestFactory");
    if (!(original instanceof JdkClientHttpRequestFactory jdk))
      throw new IllegalStateException("UNSAFE_REQUEST");
    ClientHttpRequestFactory guard =
        (uri, method) -> {
          if (!endpoint.equals(uri) || method != HttpMethod.POST)
            throw new IllegalStateException("UNSAFE_REQUEST");
          take(); // Below VulcanClient, before the existing JDK factory can create/dispatch a
          // request.
          return jdk.createRequest(
              uri, method); // No header, cookie, TLS, timeout or protocol changes.
        };
    ReflectionTestUtils.setField(rest, "clientRequestFactory", guard);
  }
}
