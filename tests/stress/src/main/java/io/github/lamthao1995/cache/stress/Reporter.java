package io.github.lamthao1995.cache.stress;

import org.HdrHistogram.Histogram;

import java.io.PrintStream;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Pretty-prints the final stress report in the same shape as the reference Go tool. */
public final class Reporter {

    private final PrintStream out;

    public Reporter(PrintStream out) {
        this.out = out;
    }

    public void print(StressConfig cfg, Duration actualDuration,
                      Map<OpKind, Histogram> hist, EnumMap<OpKind, Long> errors) {

        long writes  = hist.get(OpKind.WRITE).getTotalCount();
        long updates = hist.get(OpKind.UPDATE).getTotalCount();
        long hits    = hist.get(OpKind.READ_HIT).getTotalCount();
        long misses  = hist.get(OpKind.READ_MISS).getTotalCount();
        long errs    = errors.values().stream().mapToLong(Long::longValue).sum();
        long total   = writes + updates + hits + misses;

        double seconds = Math.max(0.001, actualDuration.toMillis() / 1_000.0);
        double rps     = total / seconds;
        double readN   = hits + misses;
        double hitRatio = readN == 0 ? 0.0 : hits / readN;

        out.println("---- stress report ----");
        out.printf(Locale.ROOT, "duration       : %.2fs%n", seconds);
        out.printf(Locale.ROOT, "target rps     : %d%n", cfg.rps());
        out.printf(Locale.ROOT, "achieved rps   : %,.1f (%,d ops)%n", rps, total);
        out.printf(Locale.ROOT, "errors         : %,d (%.3f%%)%n",
                errs, total == 0 ? 0.0 : 100.0 * errs / (total + errs));
        out.printf(Locale.ROOT, "cache hit ratio: %.2f%% (%,d hits / %,d misses)%n",
                100.0 * hitRatio, hits, misses);
        out.println();
        out.printf("%-12s %10s %10s %10s %10s %10s %10s%n",
                "operation", "count", "avg", "p50", "p95", "p99", "max");
        line(OpKind.WRITE,     "write (POST)",       hist);
        line(OpKind.UPDATE,    "update (PUT)",       hist);
        line(OpKind.READ_HIT,  "read HIT  (cache)",  hist);
        line(OpKind.READ_MISS, "read MISS (db)",     hist);
        out.println();
    }

    private void line(OpKind kind, String label, Map<OpKind, Histogram> hist) {
        Histogram h = hist.get(kind);
        if (h.getTotalCount() == 0) {
            out.printf("%-18s %10s %10s %10s %10s %10s %10s%n", label, "0", "-", "-", "-", "-", "-");
            return;
        }
        out.printf(Locale.ROOT, "%-18s %,10d %10s %10s %10s %10s %10s%n",
                label,
                h.getTotalCount(),
                fmt(h.getMean()),
                fmt(h.getValueAtPercentile(50)),
                fmt(h.getValueAtPercentile(95)),
                fmt(h.getValueAtPercentile(99)),
                fmt(h.getMaxValue())
        );
    }

    private static String fmt(double micros) {
        if (micros >= 1_000_000) return String.format(Locale.ROOT, "%.1fs",  micros / 1_000_000.0);
        if (micros >= 1_000)     return String.format(Locale.ROOT, "%.1fms", micros / 1_000.0);
        return String.format(Locale.ROOT, "%dµs", (long) micros);
    }
}
