package io.github.lamthao1995.cache.stress;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReporterTest {

    private static Map<OpKind, Histogram> emptyHistograms() {
        Map<OpKind, Histogram> m = new EnumMap<>(OpKind.class);
        for (OpKind k : OpKind.values()) {
            m.put(k, new Histogram(120_000_000L, 3));
        }
        return m;
    }

    private static EnumMap<OpKind, Long> errors(long total) {
        EnumMap<OpKind, Long> e = new EnumMap<>(OpKind.class);
        for (OpKind k : OpKind.values()) e.put(k, 0L);
        e.put(OpKind.ERROR, total);
        return e;
    }

    private static String render(Map<OpKind, Histogram> hist, EnumMap<OpKind, Long> errs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        new Reporter(new PrintStream(baos)).print(
                StressConfig.defaults(),
                Duration.ofSeconds(10),
                hist,
                errs);
        return baos.toString(StandardCharsets.UTF_8);
    }

    @Test
    void all_errors_reports_100_percent() {
        // Every request errored — none of the histograms have any sample.
        String out = render(emptyHistograms(), errors(50));
        assertThat(out).contains("errors         : 50 (100.000%)");
    }

    @Test
    void mixed_success_and_errors_reports_correct_percentage() {
        Map<OpKind, Histogram> h = emptyHistograms();
        // 90 successful read HITs, 10 errors → 10 / (90 + 10) = 10%.
        for (int i = 0; i < 90; i++) h.get(OpKind.READ_HIT).recordValue(1_000);
        String out = render(h, errors(10));
        assertThat(out).contains("errors         : 10 (10.000%)");
    }

    @Test
    void zero_errors_zero_ops_does_not_divide_by_zero() {
        // Empty stress run (e.g. seed crashed before recording started).
        String out = render(emptyHistograms(), errors(0));
        assertThat(out).contains("errors         : 0 (0.000%)");
    }

    @Test
    void hit_ratio_appears_when_reads_happen() {
        Map<OpKind, Histogram> h = emptyHistograms();
        for (int i = 0; i < 80; i++) h.get(OpKind.READ_HIT).recordValue(1_000);
        for (int i = 0; i < 20; i++) h.get(OpKind.READ_MISS).recordValue(2_000);
        String out = render(h, errors(0));
        assertThat(out).contains("cache hit ratio: 80.00% (80 hits / 20 misses)");
    }
}
