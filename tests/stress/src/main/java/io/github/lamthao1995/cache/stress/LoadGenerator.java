package io.github.lamthao1995.cache.stress;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop, fixed-rate load generator.
 *
 * <p>Each worker is a virtual thread that paces itself to {@code rps / workers} ops per
 * second using {@link LockSupport#parkNanos(long)}. We deliberately avoid a single shared
 * scheduler (which would coordinate-omit if a request stalls): every worker keeps trying
 * to hit its own per-op deadline, so a slow request leaks only that worker's slot, not
 * the whole experiment's clock.</p>
 *
 * <p>The mix is decided per op: {@code read} with prob {@code readRatio}, otherwise
 * {@code update} with prob {@code updateRatio / (1-readRatio)}, otherwise a fresh write.</p>
 */
public final class LoadGenerator {

    private final StressConfig cfg;
    private final Workload workload;
    private final LatencyRecorder recorder = new LatencyRecorder();
    private final ConcurrentHashMap<OpKind, LongAdder> errorByCause = new ConcurrentHashMap<>();

    public LoadGenerator(StressConfig cfg, Workload workload) {
        this.cfg      = cfg;
        this.workload = workload;
        for (OpKind k : OpKind.values()) errorByCause.put(k, new LongAdder());
    }

    /** Runs warmup (untimed) then the timed window, returns the actual elapsed duration. */
    public Duration run() throws InterruptedException {
        if (!cfg.warmup().isZero()) {
            runWindow(cfg.warmup(), false);
            // discard the recorder snapshot after warmup so percentiles aren't poisoned
            recorder.snapshot();
            for (LongAdder a : errorByCause.values()) a.reset();
        }
        long start = System.nanoTime();
        runWindow(cfg.duration(), true);
        return Duration.ofNanos(System.nanoTime() - start);
    }

    public LatencyRecorder recorder() {
        return recorder;
    }

    public EnumMap<OpKind, Long> errors() {
        EnumMap<OpKind, Long> snap = new EnumMap<>(OpKind.class);
        errorByCause.forEach((k, v) -> snap.put(k, v.sum()));
        return snap;
    }

    private void runWindow(Duration window, boolean record) throws InterruptedException {
        long deadlineNs   = System.nanoTime() + window.toNanos();
        long perWorkerNs  = Math.max(1L, 1_000_000_000L * cfg.workers() / cfg.rps());
        CountDownLatch done = new CountDownLatch(cfg.workers());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int w = 0; w < cfg.workers(); w++) {
                final long seed = cfg.seed() ^ ((long) w * 0x9E3779B97F4A7C15L);
                pool.submit(() -> {
                    Random rng = new Random(seed);
                    long next = System.nanoTime();
                    try {
                        while (System.nanoTime() < deadlineNs) {
                            Workload.Outcome o = pickAndExecute(rng);
                            if (record) {
                                if (o.kind() == OpKind.ERROR) {
                                    errorByCause.get(OpKind.ERROR).increment();
                                } else {
                                    recorder.record(o.kind(), o.nanos());
                                }
                            }
                            next += perWorkerNs;
                            long sleep = next - System.nanoTime();
                            if (sleep > 0) LockSupport.parkNanos(sleep);
                            else next = System.nanoTime();   // we're behind, reset pacer
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        }
    }

    private Workload.Outcome pickAndExecute(Random rng) {
        double r = rng.nextDouble();
        if (r < cfg.readRatio())                              return workload.doRead(rng);
        if (r < cfg.readRatio() + cfg.updateRatio())          return workload.doUpdate(rng);
        return workload.doWrite(rng);
    }
}
