package ru.corvex.auth.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Минимальный последовательный HTTP/1.1 сервер для контрактных тестов RestClient. */
final class FakeHttpServer implements AutoCloseable {

    @FunctionalInterface
    interface Handler {
        Response handle(Request request);
    }

    record Request(String method, String target, Map<String, String> headers, String body) {
        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    record Response(int status, String contentType, String body) {
        static Response json(int status, String body) {
            return new Response(status, "application/json", body);
        }
    }

    private final ServerSocket socket;
    private final Handler handler;
    private final Thread thread;
    private final List<Request> requests = new ArrayList<>();
    private volatile boolean closed;

    FakeHttpServer(Handler handler) throws IOException {
        this.handler = handler;
        this.socket = new ServerSocket();
        this.socket.bind(new InetSocketAddress("127.0.0.1", 0));
        this.thread = new Thread(this::serve, "fake-auth-http-server");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    Request lastRequest() {
        synchronized (requests) {
            return requests.get(requests.size() - 1);
        }
    }

    private void serve() {
        while (!closed) {
            try (Socket client = socket.accept()) {
                Request request = readRequest(client.getInputStream());
                synchronized (requests) {
                    requests.add(request);
                }
                Response response = handler.handle(request);
                writeResponse(client.getOutputStream(), response);
            } catch (IOException e) {
                if (!closed) {
                    throw new IllegalStateException("Fake HTTP server failed", e);
                }
            }
        }
    }

    private Request readRequest(InputStream input) throws IOException {
        String headerBlock = readUntilHeaderEnd(input);
        String[] lines = headerBlock.split("\r\n");
        String[] first = lines[0].split(" ", 3);
        Map<String, String> headers = new LinkedHashMap<>();
        int contentLength = 0;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                headers.put(name, value);
                if ("content-length".equals(name)) {
                    contentLength = Integer.parseInt(value);
                }
            }
        }
        byte[] body = input.readNBytes(contentLength);
        return new Request(first[0], first[1], Map.copyOf(headers),
                new String(body, StandardCharsets.UTF_8));
    }

    private String readUntilHeaderEnd(InputStream input) throws IOException {
        StringBuilder result = new StringBuilder();
        int matched = 0;
        int b;
        while ((b = input.read()) != -1) {
            result.append((char) b);
            if ((matched == 0 || matched == 2) && b == '\r') {
                matched++;
            } else if ((matched == 1 || matched == 3) && b == '\n') {
                matched++;
                if (matched == 4) {
                    return result.substring(0, result.length() - 4);
                }
            } else {
                matched = b == '\r' ? 1 : 0;
            }
        }
        return result.toString();
    }

    private void writeResponse(OutputStream output, Response response) throws IOException {
        byte[] body = response.body() == null
                ? new byte[0]
                : response.body().getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + response.status() + " Test\r\n"
                + "Content-Type: " + response.contentType() + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        socket.close();
    }
}
