package com.clawbot.wechatbot.tools.webaccess;

import com.clawbot.wechatbot.tools.webPageTool.WebPageExtractClient;
import com.clawbot.wechatbot.tools.webPageTool.WebPageExtractRequest;
import com.clawbot.wechatbot.tools.webPageTool.WebPageExtractResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeHttpFetcherTests {
    private HttpServer server;
    private URI baseUri;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void stopsReadingAChunkedResponseAsSoonAsTheByteLimitIsExceeded() {
        server.createContext("/large", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("x".repeat(256).getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        SafeHttpFetcher fetcher = fetcher(uri -> { }, 32, 3);

        assertThrows(
            ResponseTooLargeException.class,
            () -> fetcher.fetchText(baseUri.resolve("/large"))
        );
    }

    @Test
    void validatesEveryRedirectBeforeConnectingToTheNextTarget() {
        AtomicInteger privateTargetHits = new AtomicInteger();
        server.createContext("/redirect", exchange ->
            redirect(exchange, "/private"));
        server.createContext("/private", exchange -> {
            privateTargetHits.incrementAndGet();
            sendText(exchange, "should not be reached");
        });
        List<String> validatedPaths = new ArrayList<>();
        SafeHttpFetcher fetcher = fetcher(uri -> {
            validatedPaths.add(uri.getPath());
            if ("/private".equals(uri.getPath())) {
                throw new UnsafeUrlException("blocked redirect target");
            }
        }, 1024, 3);

        assertThrows(
            UnsafeUrlException.class,
            () -> fetcher.fetchText(baseUri.resolve("/redirect"))
        );
        assertEquals(List.of("/redirect", "/private"), validatedPaths);
        assertEquals(0, privateTargetHits.get());
    }

    @Test
    void rejectsRedirectChainsThatExceedTheConfiguredLimit() {
        server.createContext("/one", exchange -> redirect(exchange, "/two"));
        server.createContext("/two", exchange -> redirect(exchange, "/three"));
        server.createContext("/three", exchange -> sendText(exchange, "done"));
        SafeHttpFetcher fetcher = fetcher(uri -> { }, 1024, 1);

        IOException error = assertThrows(
            IOException.class,
            () -> fetcher.fetchText(baseUri.resolve("/one"))
        );
        assertTrue(error.getMessage().contains("重定向次数"));
    }

    @Test
    void discardsUnsupportedBinaryContentWithoutReturningIt() {
        server.createContext("/binary", exchange -> {
            byte[] bytes = new byte[512];
            exchange.getResponseHeaders().add(
                "Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        SafeHttpFetcher fetcher = fetcher(uri -> { }, 1024, 3);

        IOException error = assertThrows(
            IOException.class,
            () -> fetcher.fetchText(baseUri.resolve("/binary"))
        );
        assertTrue(error.getMessage().contains("Content-Type"));
    }

    @Test
    void clampsTheFunctionArgumentToTheConfiguredBodyCharacterLimit() {
        server.createContext("/page", exchange ->
            sendText(exchange, "<html><body>abcdefghij</body></html>"));
        SafeHttpFetcher fetcher = fetcher(uri -> { }, 1024, 3);
        WebPageExtractClient client = new WebPageExtractClient(fetcher, 5);

        WebPageExtractResult result = client.extract(
            new WebPageExtractRequest(baseUri.resolve("/page").toString(), 1000));

        assertTrue(result.isSuccess());
        assertEquals("abcde", result.getBodyText());
        assertFalse(result.getBodyText().contains("f"));
    }

    private SafeHttpFetcher fetcher(
        UriAccessValidator validator, int maxBytes, int maxRedirects
    ) {
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        return new SafeHttpFetcher(
            http,
            validator,
            Duration.ofSeconds(3),
            maxBytes,
            maxRedirects,
            "ClawBot-Test/1.0"
        );
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void sendText(HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
