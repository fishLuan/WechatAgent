package com.clawbot.wechatbot.feature.bilibili.source.client;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BilibiliHttpClientTests {

    @Test
    void sendsConfiguredCookie() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        HttpResponse<String> response = response(200, "{\"code\":0}");
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        when(delegate.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(invocation -> {
                captured.set(invocation.getArgument(0));
                return response;
            });
        BilibiliHttpClient client = new BilibiliHttpClient(
            delegate, Duration.ofSeconds(1), 0,
            "SESSDATA=test; bili_jct=test", Duration.ofMinutes(30));

        client.getText("https://api.bilibili.com/test");

        assertEquals("SESSDATA=test; bili_jct=test",
            captured.get().headers().firstValue("Cookie").orElseThrow());
    }

    @Test
    void loggedInSearchSkipsAnonymousSessionAndUsesConfiguredUserAgent()
        throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        HttpResponse<String> response = response(200, "{\"code\":0}");
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        when(delegate.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenAnswer(invocation -> {
                captured.set(invocation.getArgument(0));
                return response;
            });
        BilibiliHttpClient client = new BilibiliHttpClient(
            delegate, Duration.ofSeconds(1), 0,
            "SESSDATA=test; buvid3=test", "MyBrowser/1.0",
            Duration.ofMinutes(30), 100);

        client.getAnonymousSearchText("https://api.bilibili.com/search");

        verify(delegate, times(1)).send(
            any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertEquals("MyBrowser/1.0",
            captured.get().headers().firstValue("User-Agent").orElseThrow());
    }

    @Test
    void opensCircuitAndDoesNotRetryAfter412() throws Exception {
        HttpClient delegate = mock(HttpClient.class);
        HttpResponse<String> limitedResponse = response(412, "risk control");
        when(delegate.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(limitedResponse);
        BilibiliHttpClient client = new BilibiliHttpClient(
            delegate, Duration.ofSeconds(1), 2, "", Duration.ofMinutes(30));

        assertThrows(BilibiliAccessLimitedException.class,
            () -> client.getAnonymousSearchText("https://api.bilibili.com/search"));
        assertThrows(BilibiliAccessLimitedException.class,
            () -> client.getAnonymousSearchText("https://api.bilibili.com/search"));

        // One homepage initialization plus one search request; no retry and no second search.
        verify(delegate, times(2)).send(
            any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
