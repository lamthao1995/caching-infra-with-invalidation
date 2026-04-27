package io.github.lamthao1995.cache.stress;

import org.HdrHistogram.Histogram;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyRecorderTest {

    @Test
    void records_into_correct_bucket_with_microsecond_resolution() {
        LatencyRecorder rec = new LatencyRecorder();
        for (int i = 0; i < 1000; i++) rec.record(OpKind.READ_HIT, 500_000L);   // 500µs
        for (int i = 0; i < 100; i++)  rec.record(OpKind.READ_MISS, 5_000_000L); // 5ms
        rec.record(OpKind.WRITE, 1_500_000L);                                    // 1.5ms

        Map<OpKind, Histogram> snap = rec.snapshot();
        assertThat(snap.get(OpKind.READ_HIT).getTotalCount()).isEqualTo(1000);
        assertThat(snap.get(OpKind.READ_MISS).getTotalCount()).isEqualTo(100);
        assertThat(snap.get(OpKind.WRITE).getTotalCount()).isEqualTo(1);
        assertThat(snap.get(OpKind.READ_HIT).getMean()).isBetween(490.0, 510.0);   // ~500µs
        assertThat(snap.get(OpKind.READ_MISS).getMean()).isBetween(4_900.0, 5_100.0); // ~5ms
    }

    @Test
    void snapshot_resets_so_subsequent_samples_dont_double_count() {
        LatencyRecorder rec = new LatencyRecorder();
        rec.record(OpKind.WRITE, 1_000_000L);
        Histogram first = rec.snapshot().get(OpKind.WRITE);
        Histogram second = rec.snapshot().get(OpKind.WRITE);

        assertThat(first.getTotalCount()).isEqualTo(1);
        assertThat(second.getTotalCount()).isEqualTo(0);
    }
}
