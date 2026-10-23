package ru.corvex.mailsmtp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Минимальный JDK HTTP-сервер для проверки внутренних HTTP-клиентов без внешних сервисов. */
public final class RecordingHttpServer implements AutoCloseable {

    public record Request(String method, URI uri, Headers headers, byte[] body) {
        public String header(String name) {
            return headers.getFirst(name);
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public String path() {
            return uri.getPath();
        }
    }

    public record Response(int status, byte[] body, String contentType) {
        public static Response json(int status, String json) {
            return new Response(status, json.getBytes(StandardCharsets.UTF_8), "application/json");
        }

        public static Response bytes(int status, byte[] body) {
            return new Response(status, body, "application/octet-stream");
        }

        public static Response empty(int status) {
            return new Response(status, new byte[0], "text/plain");
        }
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<Function<Request, Response>> handler;

    public RecordingHttpServer(Function<Request, Response> handler) throws IOException {
        this.handler = new AtomicReference<>(handler);
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "recording-http-server");
            thread.setDaemon(true);
            return thread;
        });
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Headers headers = new Headers();
            headers.putAll(exchange.getRequestHeaders());
            Request request = new Request(
                    exchange.getRequestMethod(), exchange.getRequestURI(), headers, body);
            requests.add(request);

            Response response;
            try {
                response = this.handler.get().apply(request);
            } catch (RuntimeException e) {
                response = Response.json(500, "{\"error\":\"stub failure\"}");
            }
            if (response.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", response.contentType());
            }
            if (response.body().length == 0) {
                exchange.sendResponseHeaders(response.status(), -1);
            } else {
                exchange.sendResponseHeaders(response.status(), response.body().length);
                exchange.getResponseBody().write(response.body());
            }
            exchange.close();
        });
        this.server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setHandler(Function<Request, Response> handler) {
        this.handler.set(handler);
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
