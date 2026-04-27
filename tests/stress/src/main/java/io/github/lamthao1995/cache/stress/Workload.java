package io.github.lamthao1995.cache.stress;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lamthao1995.cache.common.model.Item;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Encapsulates the HTTP calls the load generator makes. Stateless apart from the
 * pre-seeded {@code ids} list: every worker reads/writes through this class so all the
 * URL templating, Jackson plumbing, and X-Cache header parsing lives in one place.
 *
 * <p>The seeding step (POST keyspace items, collect IDs) is also done here so the IDs
 * the readers will hit actually exist in MySQL before we start measuring.</p>
 */
public final class Workload {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI writeBase;
    private final URI readBase;
    private final Duration httpTimeout;
    /**
     * Backed by a synchronized list — reads use {@code rng.nextInt(size())} which is
     * safe under contention, and writes only happen during seed (single-threaded outside
     * the executor below). Cheaper than {@code CopyOnWriteArrayList} for our access mix.
     */
    private final List<Long> ids;

    public Workload(HttpClient http, ObjectMapper mapper, StressConfig cfg) {
        this.http        = http;
        this.mapper      = mapper;
        this.writeBase   = URI.create(cfg.writeTarget());
        this.readBase    = URI.create(cfg.readTarget());
        this.httpTimeout = cfg.httpTimeout();
        this.ids         = Collections.synchronizedList(new ArrayList<>(cfg.keyspace()));
    }

    /** Number of seeded IDs available for read/update operations. */
    public int keyspace() {
        return ids.size();
    }

    /**
     * Insert {@code n} fresh items so the read mix has something to hit. Parallelised on
     * a virtual-thread executor — sequential POST at ~5 ms RTT would take ~50 s for a
     * 10k keyspace, which dominates a 60 s stress run; this finishes in 1–2 s.
     */
    public void seed(int n) throws Exception {
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Long>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                futures.add(CompletableFuture.supplyAsync(this::seedOne, exec));
            }
            for (CompletableFuture<Long> f : futures) {
                ids.add(f.join());
            }
        }
    }

    private long seedOne() {
        try {
            byte[] body = mapper.writeValueAsBytes(randomItem());
            HttpResponse<byte[]> resp = http.send(
                    HttpRequest.newBuilder(writeBase.resolve("/api/v1/items"))
                            .timeout(httpTimeout)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("seed POST failed: HTTP " + resp.statusCode());
            }
            return mapper.readValue(resp.body(), Item.class).id();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public Outcome doWrite(Random rng) {
        try {
            byte[] body = mapper.writeValueAsBytes(randomItem());
            long t0 = System.nanoTime();
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(writeBase.resolve("/api/v1/items"))
                            .timeout(httpTimeout)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            long elapsed = System.nanoTime() - t0;
            return new Outcome(resp.statusCode() / 100 == 2 ? OpKind.WRITE : OpKind.ERROR, elapsed);
        } catch (Exception ex) {
            return new Outcome(OpKind.ERROR, 0L);
        }
    }

    public Outcome doUpdate(Random rng) {
        if (ids.isEmpty()) return doWrite(rng);
        long id = pickId(rng);
        try {
            byte[] body = mapper.writeValueAsBytes(randomItem());
            long t0 = System.nanoTime();
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(writeBase.resolve("/api/v1/items/" + id))
                            .timeout(httpTimeout)
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            long elapsed = System.nanoTime() - t0;
            return new Outcome(resp.statusCode() / 100 == 2 ? OpKind.UPDATE : OpKind.ERROR, elapsed);
        } catch (Exception ex) {
            return new Outcome(OpKind.ERROR, 0L);
        }
    }

    public Outcome doRead(Random rng) {
        if (ids.isEmpty()) return new Outcome(OpKind.ERROR, 0L);
        long id = pickId(rng);
        try {
            long t0 = System.nanoTime();
            HttpResponse<Void> resp = http.send(
                    HttpRequest.newBuilder(readBase.resolve("/api/v1/items/" + id))
                            .timeout(httpTimeout)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            long elapsed = System.nanoTime() - t0;
            if (resp.statusCode() / 100 != 2) return new Outcome(OpKind.ERROR, elapsed);
            String cache = resp.headers().firstValue("X-Cache").orElse("MISS");
            return new Outcome(cache.equalsIgnoreCase("HIT") ? OpKind.READ_HIT : OpKind.READ_MISS, elapsed);
        } catch (Exception ex) {
            return new Outcome(OpKind.ERROR, 0L);
        }
    }

    private long pickId(Random rng) {
        // Snapshot size to avoid a torn read against the synchronized list while seed/run
        // overlap (they don't today, but cheap defence).
        int size = ids.size();
        return ids.get(rng.nextInt(size));
    }

    /** Random item generator using {@link ThreadLocalRandom} so it's safe to call from any thread. */
    private static Item randomItem() {
        ThreadLocalRandom tlr = ThreadLocalRandom.current();
        return new Item(
                null,
                "item-" + tlr.nextInt(1_000_000),
                "desc-" + tlr.nextInt(1_000_000),
                tlr.nextLong(0, 1_000_000),
                "USD",
                tlr.nextInt(0, 1_000),
                null,
                null
        );
    }

    public record Outcome(OpKind kind, long nanos) {
    }
}
