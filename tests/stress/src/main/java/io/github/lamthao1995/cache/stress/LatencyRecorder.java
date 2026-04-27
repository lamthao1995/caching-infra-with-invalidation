package io.github.lamthao1995.cache.stress;

import org.HdrHistogram.Histogram;
import org.HdrHistogram.Recorder;

import java.util.EnumMap;
import java.util.Map;

/**
 * Per-{@link OpKind} latency recorder. We use HdrHistogram's {@link Recorder} because it is
 * the only Java structure that gives us thread-safe, lock-free recording at the rates we
 * push (10k+ ops/s per worker fan-in) while preserving accurate p99/p99.9 values.
 *
 * <p>Time unit: microseconds. Tracked range: 1µs..120s with 3 significant digits — that's
 * a constant ~few KB of memory per histogram and below 1% percentile error.</p>
 */
public final class LatencyRecorder {

    private static final long MAX_MICROS = 120_000_000L;
    private static final int  PRECISION  = 3;

    private final Map<OpKind, Recorder> recorders = new EnumMap<>(OpKind.class);

    public LatencyRecorder() {
        for (OpKind k : OpKind.values()) {
            recorders.put(k, new Recorder(MAX_MICROS, PRECISION));
        }
    }

    public void record(OpKind kind, long nanos) {
        long micros = Math.max(1, nanos / 1_000);
        recorders.get(kind).recordValue(Math.min(micros, MAX_MICROS));
    }

    /** Snapshot all per-kind histograms. Caller owns the returned histograms. */
    public Map<OpKind, Histogram> snapshot() {
        Map<OpKind, Histogram> out = new EnumMap<>(OpKind.class);
        for (var e : recorders.entrySet()) {
            out.put(e.getKey(), e.getValue().getIntervalHistogram());
        }
        return out;
    }
}
