package com.taskflow.orders.payment.config;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.BufferedSink;

public final class PaymentHttp {
    private PaymentHttp() { }

    public static OkHttpClient client(Duration connectTimeout, Duration requestTimeout) {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout).callTimeout(requestTimeout)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .addInterceptor(chain -> {
                    var request = chain.request();
                    var body = request.body();
                    if (request.method().equals("POST") && body != null) {
                        // Mark POST as one-shot even when the response invites a transport retry.
                        request = request.newBuilder().post(new RequestBody() {
                            @Override public MediaType contentType() { return body.contentType(); }
                            @Override public long contentLength() throws IOException { return body.contentLength(); }
                            @Override public void writeTo(BufferedSink sink) throws IOException { body.writeTo(sink); }
                            @Override public boolean isOneShot() { return true; }
                        }).build();
                    }
                    return chain.proceed(request);
                }).build();
    }

    public static void requireHttps(URI url, String path) {
        if (url == null || !"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null
                || (path != null && !path.equals(url.getRawPath()))) {
            throw new IllegalArgumentException("Payment endpoints must use HTTPS without credentials or query parameters");
        }
    }
}
