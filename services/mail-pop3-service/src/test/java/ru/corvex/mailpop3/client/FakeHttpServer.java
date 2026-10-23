package ru.corvex.mailpop3.client;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

final class FakeHttpServer implements AutoCloseable {

    @FunctionalInterface
    interface Handler {
        Response handle(Request request) throws Exception;
    }

    record Request(String method, String path, String headers, byte[] body) {
        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            for (String line : headers.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
    }

    record Response(int status, byte[] body, String contentType) {
        static Response json(String body) {
            return new Response(200, body.getBytes(StandardCharsets.UTF_8), "application/json");
        }

        static Response bytes(byte[] body) {
            return new Response(200, body, "application/octet-stream");
        }

        static Response status(int status) {
            return new Response(status, new byte[0], "text/plain");
        }

        static Response status(int status, String body) {
            return new Response(status, body.getBytes(StandardCharsets.UTF_8), "application/json");
        }
    }

    private final ServerSocket server;
    private final Handler handler;
    private final Thread thread;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean closed;

    FakeHttpServer(Handler handler) throws IOException {
        this.handler = handler;
        this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.thread = new Thread(this::serveLoop, "fake-pop3-http");
        this.thread.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    void rethrowFailure() throws Exception {
        Throwable error = failure.get();
        if (error instanceof Exception exception) {
            throw exception;
        }
        if (error instanceof Error err) {
            throw err;
        }
        if (error != null) {
            throw new AssertionError(error);
        }
    }

    private void serveLoop() {
        while (!closed) {
            try {
                Socket socket = server.accept();
                handle(socket);
            } catch (SocketException e) {
                if (!closed) {
                    failure.compareAndSet(null, e);
                }
                return;
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        try (Socket accepted = socket) {
            accepted.setSoTimeout(5_000);
            BufferedInputStream input = new BufferedInputStream(accepted.getInputStream());
            Request request = readRequest(input);
            requests.add(request);
            Response response;
            try {
                response = handler.handle(request);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
                response = Response.status(500);
            }
            writeResponse(accepted, response);
        }
    }

    private static Request readRequest(InputStream input) throws IOException {
        String headers = readHeaders(input);
        String firstLine = headers.lines().findFirst().orElse("");
        String[] parts = firstLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "";
        int length = contentLength(headers);
        byte[] body = length == 0 ? new byte[0] : input.readNBytes(length);
        if (body.length != length) {
            throw new IOException("short HTTP request body");
        }
        return new Request(method, path, headers, body);
    }

    private static int contentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                return Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        return 0;
    }

    private static String readHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        int value;
        while ((value = input.read()) != -1) {
            bytes.write(value);
            if (value == terminator[matched]) {
                matched++;
                if (matched == terminator.length) {
                    break;
                }
            } else {
                matched = value == '\r' ? 1 : 0;
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    private static void writeResponse(Socket socket, Response response) throws IOException {
        String headers = "HTTP/1.1 " + response.status() + " " + statusText(response.status()) + "\r\n"
                + "Content-Length: " + response.body().length + "\r\n"
                + "Content-Type: " + response.contentType() + "\r\n"
                + "Connection: close\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(response.body());
        socket.getOutputStream().flush();
    }

    private static String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 204 -> "No Content";
            case 401 -> "Unauthorized";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }

    @Override
    public void close() throws Exception {
        closed = true;
        server.close();
        thread.join(5_000);
    }
}
