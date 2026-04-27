package io.github.lamthao1995.cache.stress;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * Single shared {@link HttpClient}. JDK's HTTP/1.1 pool reuses connections per host, so
 * this is the cheapest way to amortise TCP setup across millions of requests. Backed by a
 * virtual-thread executor — every blocking response read parks instead of pinning an OS
 * thread, which is what lets a 32-worker generator drive 50k+ rps on a laptop.
 */
public final class HttpClients {

    private HttpClients() {
    }

    public static HttpClient build(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
