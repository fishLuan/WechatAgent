package com.clawbot.wechatbot.tools.webaccess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public class SafeHttpFetcher {
    private final HttpClient http;
    private final UriAccessValidator accessValidator;
    private final Duration requestTimeout;
    private final int maxResponseBytes;
    private final int maxRedirects;
    private final String userAgent;

    public SafeHttpFetcher(
        HttpClient http,
        UriAccessValidator accessValidator,
        Duration requestTimeout,
        int maxResponseBytes,
        int maxRedirects,
        String userAgent
    ) {
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be greater than 0");
        }
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("maxRedirects must not be negative");
        }
        this.http = http;
        this.accessValidator = accessValidator;
        this.requestTimeout = requestTimeout;
        this.maxResponseBytes = maxResponseBytes;
        this.maxRedirects = maxRedirects;
        this.userAgent = userAgent;
    }

    public TextFetchResponse fetchText(URI startUri) throws Exception {
        URI current = startUri;
        int redirectCount = 0;
        Set<URI> visited = new HashSet<>();

        while (true) {
            if (!visited.add(current.normalize())) {
                throw new IOException("检测到循环重定向");
            }
            accessValidator.validate(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,text/plain,application/xml,text/xml;q=0.9")
                .header("Accept-Encoding", "identity")
                .GET()
                .build();

            HttpResponse<byte[]> response;
            try {
                response = http.send(request, this::bodyHandler);
            } catch (IOException e) {
                ResponseTooLargeException tooLarge = findCause(
                    e, ResponseTooLargeException.class);
                if (tooLarge != null) throw tooLarge;
                throw e;
            }

            int status = response.statusCode();
            if (isRedirect(status)) {
                URI redirect = redirectTarget(current, response.headers());
                if (redirect == null) {
                    return new TextFetchResponse(
                        status, current, contentType(response.headers()), new byte[0],
                        redirectCount);
                }
                if (redirectCount >= maxRedirects) {
                    throw new IOException("网页重定向次数超过系统限制：" + maxRedirects);
                }
                current = redirect;
                redirectCount++;
                continue;
            }

            if (status >= 200 && status < 300) {
                validateReadableHeaders(response.headers());
            }
            return new TextFetchResponse(
                status,
                current,
                contentType(response.headers()),
                response.body(),
                redirectCount
            );
        }
    }

    public RedirectResolution resolveRedirects(URI startUri, int requestedMaxRedirects)
            throws Exception {
        int redirectLimit = Math.min(Math.max(requestedMaxRedirects, 0), maxRedirects);
        URI current = startUri;
        int redirectCount = 0;
        Set<URI> visited = new HashSet<>();

        while (true) {
            if (!visited.add(current.normalize())) {
                throw new IOException("检测到循环重定向");
            }
            accessValidator.validate(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept-Encoding", "identity")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<Void> response =
                http.send(request, HttpResponse.BodyHandlers.discarding());

            if (!isRedirect(response.statusCode())) {
                return new RedirectResolution(current, response.statusCode(), redirectCount);
            }
            URI redirect = redirectTarget(current, response.headers());
            if (redirect == null) {
                return new RedirectResolution(current, response.statusCode(), redirectCount);
            }
            if (redirectCount >= redirectLimit) {
                throw new IOException("短链重定向次数超过系统限制：" + redirectLimit);
            }
            current = redirect;
            redirectCount++;
        }
    }

    private HttpResponse.BodySubscriber<byte[]> bodyHandler(
        HttpResponse.ResponseInfo responseInfo
    ) {
        int status = responseInfo.statusCode();
        if (status < 200 || status >= 300) {
            return HttpResponse.BodySubscribers.replacing(new byte[0]);
        }
        if (!hasReadableContentType(responseInfo.headers())
                || !hasSupportedContentEncoding(responseInfo.headers())
                || contentLengthExceedsLimit(responseInfo.headers())) {
            return HttpResponse.BodySubscribers.replacing(new byte[0]);
        }
        return new LimitedByteArraySubscriber(maxResponseBytes);
    }

    private void validateReadableHeaders(HttpHeaders headers) throws IOException {
        String type = contentType(headers);
        if (!hasReadableContentType(headers)) {
            throw new IOException("不支持的 Content-Type：" + type);
        }
        String encoding = headers.firstValue("Content-Encoding").orElse("");
        if (!hasSupportedContentEncoding(headers)) {
            throw new IOException("不支持的 Content-Encoding：" + encoding);
        }
        if (contentLengthExceedsLimit(headers)) {
            throw new ResponseTooLargeException(maxResponseBytes);
        }
    }

    private boolean hasReadableContentType(HttpHeaders headers) {
        String value = contentType(headers).toLowerCase(Locale.ROOT);
        return value.isBlank()
            || value.startsWith("text/")
            || value.startsWith("application/xhtml+xml")
            || value.startsWith("application/xml")
            || value.contains("+xml");
    }

    private boolean hasSupportedContentEncoding(HttpHeaders headers) {
        String value = headers.firstValue("Content-Encoding")
            .orElse("")
            .trim()
            .toLowerCase(Locale.ROOT);
        return value.isBlank() || "identity".equals(value);
    }

    private boolean contentLengthExceedsLimit(HttpHeaders headers) {
        OptionalLong length = headers.firstValueAsLong("Content-Length");
        return length.isPresent() && length.getAsLong() > maxResponseBytes;
    }

    private String contentType(HttpHeaders headers) {
        return headers.firstValue("Content-Type").orElse("");
    }

    private URI redirectTarget(URI current, HttpHeaders headers) throws IOException {
        String location = headers.firstValue("Location").orElse("").trim();
        if (location.isEmpty()) return null;
        try {
            return current.resolve(location);
        } catch (IllegalArgumentException e) {
            throw new IOException("网页返回了无效的重定向地址", e);
        }
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303
            || status == 307 || status == 308;
    }

    private <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }

    public record TextFetchResponse(
        int statusCode,
        URI finalUri,
        String contentType,
        byte[] body,
        int redirectCount
    ) {
    }

    public record RedirectResolution(URI finalUri, int statusCode, int redirectCount) {
    }

    static final class LimitedByteArraySubscriber
            implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream output;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private int received;

        LimitedByteArraySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
            this.output = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            try {
                for (ByteBuffer buffer : buffers) {
                    int nextSize = Math.addExact(received, buffer.remaining());
                    if (nextSize > maxBytes) {
                        subscription.cancel();
                        body.completeExceptionally(
                            new ResponseTooLargeException(maxBytes));
                        return;
                    }
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    output.write(chunk);
                    received = nextSize;
                }
                subscription.request(1);
            } catch (Exception e) {
                subscription.cancel();
                body.completeExceptionally(e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }
}
