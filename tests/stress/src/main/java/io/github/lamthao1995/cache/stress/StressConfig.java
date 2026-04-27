package io.github.lamthao1995.cache.stress;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable view of CLI / env config for the stress tool.
 *
 * <p>Parsing is deliberately tiny (no third-party CLI lib): every flag is {@code --key=value}
 * or {@code --key value}, and every flag has a sensible default. {@link #fromArgs(String[])}
 * is package-private-test friendly so we can unit-test parsing without spinning up sockets.</p>
 */
public record StressConfig(
        String writeTarget,
        String readTarget,
        int rps,
        Duration duration,
        Duration warmup,
        int workers,
        int keyspace,
        double readRatio,
        double updateRatio,
        long seed,
        Duration httpTimeout
) {

    public static StressConfig defaults() {
        return new StressConfig(
                "http://localhost:8080",
                "http://localhost:8081",
                2_000,
                Duration.ofSeconds(60),
                Duration.ofSeconds(5),
                32,
                10_000,
                0.8,
                0.1,
                42L,
                // 5s default — first POST after JVM warmup (writer just booted) can exceed 2s
                // on a fresh container, and a single seed timeout aborts the whole run.
                Duration.ofSeconds(5)
        );
    }

    public static StressConfig fromArgs(String[] args) {
        Map<String, String> kv = parse(args);
        StressConfig d = defaults();
        return new StressConfig(
                kv.getOrDefault("write-target", d.writeTarget),
                kv.getOrDefault("read-target", d.readTarget),
                Integer.parseInt(kv.getOrDefault("rps", String.valueOf(d.rps))),
                parseDuration(kv.getOrDefault("duration", "60s")),
                parseDuration(kv.getOrDefault("warmup", "5s")),
                Integer.parseInt(kv.getOrDefault("workers", String.valueOf(d.workers))),
                Integer.parseInt(kv.getOrDefault("keyspace", String.valueOf(d.keyspace))),
                Double.parseDouble(kv.getOrDefault("read-ratio", String.valueOf(d.readRatio))),
                Double.parseDouble(kv.getOrDefault("update-ratio", String.valueOf(d.updateRatio))),
                Long.parseLong(kv.getOrDefault("seed", String.valueOf(d.seed))),
                parseDuration(kv.getOrDefault("http-timeout", "5s"))
        );
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                throw new IllegalArgumentException("expected --flag, got: " + a);
            }
            String key = a.substring(2);
            String val;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                val = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                val = args[++i];
            } else {
                val = "true";
            }
            out.put(key, val);
        }
        return out;
    }

    static Duration parseDuration(String s) {
        s = s.trim().toLowerCase();
        if (s.endsWith("ms")) return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
        if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
        if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
        return Duration.ofSeconds(Long.parseLong(s));
    }

    public void validate() {
        if (rps <= 0) throw new IllegalArgumentException("--rps must be > 0");
        if (workers <= 0) throw new IllegalArgumentException("--workers must be > 0");
        if (keyspace <= 0) throw new IllegalArgumentException("--keyspace must be > 0");
        if (readRatio < 0 || readRatio > 1) throw new IllegalArgumentException("--read-ratio must be in [0,1]");
        if (updateRatio < 0 || updateRatio > 1) throw new IllegalArgumentException("--update-ratio must be in [0,1]");
        if (readRatio + updateRatio > 1.0001) {
            throw new IllegalArgumentException("--read-ratio + --update-ratio must be <= 1");
        }
    }
}
