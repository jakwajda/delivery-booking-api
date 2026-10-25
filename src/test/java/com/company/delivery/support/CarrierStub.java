package com.company.delivery.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class CarrierStub implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<String, String> shipments = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    public final AtomicInteger creations = new AtomicInteger();
    public final AtomicInteger createCalls = new AtomicInteger();
    public final AtomicInteger hiddenLookups = new AtomicInteger();
    public final AtomicBoolean loseNextResponse = new AtomicBoolean();
    public volatile int status = 200;
    public volatile String lastBody;
    public volatile boolean invalidResponse;
    public volatile CountDownLatch responseGate = new CountDownLatch(0);

    public CarrierStub() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    public URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            boolean alpha = exchange.getRequestURI().getPath().startsWith("/shipments");
            if (status != 200) {
                exchange.getResponseHeaders().set("Retry-After", "2");
                respond(exchange, status, "{}");
                return;
            }
            String reference;
            if (exchange.getRequestMethod().equals("POST")) {
                createCalls.incrementAndGet();
                lastBody =
                        new String(
                                exchange.getRequestBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                var body = json.readTree(lastBody);
                reference = body.get(alpha ? "clientReference" : "reference").asString();
                if (alpha) {
                    shipments.computeIfAbsent(
                            reference, ignored -> "TRACK-" + creations.incrementAndGet());
                } else {
                    shipments.put(reference, "TRACK-" + creations.incrementAndGet());
                }
                try {
                    responseGate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (loseNextResponse.getAndSet(false)) {
                    return;
                }
            } else {
                reference =
                        alpha
                                ? exchange.getRequestURI()
                                        .getPath()
                                        .substring("/shipments/by-reference/".length())
                                : exchange.getRequestURI()
                                        .getQuery()
                                        .substring("reference=".length());
                if (hiddenLookups.getAndUpdate(n -> Math.max(0, n - 1)) > 0
                        || !shipments.containsKey(reference)) {
                    respond(exchange, 404, "{}");
                    return;
                }
            }
            String tracking = shipments.get(reference);
            String body =
                    alpha
                            ? "{\"trackingNumber\":\""
                                    + tracking
                                    + "\",\"labelUrl\":\"https://labels.example/test\"}"
                            : "{\"number\":\""
                                    + tracking
                                    + "\",\"document\":\"https://labels.example/test\"}";
            respond(exchange, 200, invalidResponse ? "{invalid" : body);
        }
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public void reset() {
        shipments.clear();
        creations.set(0);
        createCalls.set(0);
        hiddenLookups.set(0);
        loseNextResponse.set(false);
        status = 200;
        invalidResponse = false;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
