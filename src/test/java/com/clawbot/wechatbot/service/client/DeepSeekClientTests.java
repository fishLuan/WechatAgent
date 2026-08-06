package com.clawbot.wechatbot.service.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTests {
    @Test
    void retriesTransientFailureThenOpensShortCircuit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"error\":\"busy\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DeepSeekClient client = new DeepSeekClient(
                "test-key", "test-model",
                "http://localhost:" + server.getAddress().getPort() + "/chat",
                0, 100, 1, 2, 1, 30);

            Exception first = assertThrows(Exception.class, () -> client.chat(
                client.mapper().createArrayNode(), client.mapper().createArrayNode()));
            assertTrue(first.getMessage().contains("已重试 1 次"));
            assertEquals(2, calls.get());

            Exception second = assertThrows(Exception.class, () -> client.chat(
                client.mapper().createArrayNode(), client.mapper().createArrayNode()));
            assertTrue(second.getMessage().contains("暂时熔断"));
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }
}
