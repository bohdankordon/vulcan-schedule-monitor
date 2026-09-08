import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Disposable upstream only; absent from the production image. Never logs requests. */
public final class HeaderEcho {
  public static void main(String[] args) throws Exception {
    var server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext("/", exchange -> {
      var body = new StringBuilder();
      exchange.getRequestHeaders().forEach((name, values) ->
          values.forEach(value -> body.append(name.toLowerCase(java.util.Locale.ROOT))
              .append(": ").append(value).append('\n')));
      byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      try (var output = exchange.getResponseBody()) {
        output.write(bytes);
      }
    });
    server.start();
  }
}
