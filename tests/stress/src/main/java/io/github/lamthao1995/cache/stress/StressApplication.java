package io.github.lamthao1995.cache.stress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Entry point. Parses CLI flags, seeds the keyspace through the writer service, runs the
 * generator, and prints the report. Exits non-zero on validation / seeding failure so it
 * can be plugged straight into CI smoke tests.
 */
public final class StressApplication {

    public static void main(String[] args) throws Exception {
        StressConfig cfg = StressConfig.fromArgs(args);
        cfg.validate();

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        HttpClient http = HttpClients.build(cfg.httpTimeout());
        Workload   wl   = new Workload(http, mapper, cfg);

        System.out.printf("seeding %,d items via %s ...%n", cfg.keyspace(), cfg.writeTarget());
        long t0 = System.nanoTime();
        wl.seed(cfg.keyspace());
        System.out.printf("seeded in %.2fs%n%n", (System.nanoTime() - t0) / 1e9);

        System.out.printf("running %ds (warmup %ds) | rps=%d workers=%d read=%.2f update=%.2f%n%n",
                cfg.duration().toSeconds(), cfg.warmup().toSeconds(),
                cfg.rps(), cfg.workers(), cfg.readRatio(), cfg.updateRatio());

        LoadGenerator gen = new LoadGenerator(cfg, wl);
        Duration actual = gen.run();

        new Reporter(System.out).print(cfg, actual, gen.recorder().snapshot(), gen.errors());
    }
}
