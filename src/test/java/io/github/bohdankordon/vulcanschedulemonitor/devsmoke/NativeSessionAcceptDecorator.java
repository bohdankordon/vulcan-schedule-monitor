package io.github.bohdankordon.vulcanschedulemonitor.devsmoke;

import io.github.bohdankordon.vulcanschedulemonitor.vulcan.VulcanClient;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** Diagnostic-only, one-operation decorator. The original JDK request factory is retained. */
final class NativeSessionAcceptDecorator implements ClientHttpRequestFactory {
  private final JdkClientHttpRequestFactory delegate;
  private final URI endpoint;
  private final AtomicBoolean used = new AtomicBoolean();
  private boolean injected;

  private NativeSessionAcceptDecorator(JdkClientHttpRequestFactory delegate, URI endpoint) {
    this.delegate = delegate;
    this.endpoint = endpoint;
  }

  static NativeSessionAcceptDecorator install(VulcanClient client, URI endpoint) {
    Object adapter = ReflectionTestUtils.getField(client, "scheduleAdapter");
    Object transport = ReflectionTestUtils.getField(adapter, "transport");
    Object restClient = ReflectionTestUtils.getField(transport, "restClient");
    Object original = ReflectionTestUtils.getField(restClient, "clientRequestFactory");
    if (!(original instanceof JdkClientHttpRequestFactory jdk)) {
      throw new IllegalArgumentException("INVALID_REQUEST_FACTORY");
    }
    var decorated = new NativeSessionAcceptDecorator(jdk, endpoint);
    ReflectionTestUtils.setField(restClient, "clientRequestFactory", decorated);
    return decorated;
  }

  @Override
  public ClientHttpRequest createRequest(URI uri, HttpMethod method) throws IOException {
    if (method != HttpMethod.POST || !endpoint.equals(uri)) {
      throw new IllegalArgumentException("UNEXPECTED_REQUEST");
    }
    if (!used.compareAndSet(false, true)) throw new IllegalStateException("BUDGET_EXHAUSTED");
    ClientHttpRequest request = delegate.createRequest(uri, method);
    request.getHeaders().set(HttpHeaders.ACCEPT, "*/*");
    injected = true;
    return request;
  }

  boolean injected() {
    return injected;
  }
}
