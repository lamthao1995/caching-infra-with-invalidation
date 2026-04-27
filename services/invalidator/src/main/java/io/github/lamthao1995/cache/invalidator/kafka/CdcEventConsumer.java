package io.github.lamthao1995.cache.invalidator.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lamthao1995.cache.invalidator.redis.CacheInvalidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * <h2>Where the cache invalidation actually happens.</h2>
 *
 * Subscribes to {@code app.kafka.topic} (default {@code cdc.catalog.items}) which
 * Debezium populates from the MySQL binlog via Kafka Connect. For every message we:
 *
 * <ol>
 *   <li>Parse the Debezium JSON envelope to find the operation type and row body.</li>
 *   <li>Pluck the {@code id} primary key out of {@code before} (deletes) or {@code after}
 *       (creates / updates / snapshot reads).</li>
 *   <li>Call {@link CacheInvalidator#invalidate(long)}, which is a single
 *       {@code DEL v1:item:{id}} on Redis.</li>
 * </ol>
 *
 * <h3>Concurrency and ordering</h3>
 * Debezium partitions by primary key, and Kafka guarantees ordering inside a partition,
 * so events for the same row never overtake each other. Multiple replicas of this consumer
 * just split the partitions between them — there is no cross-row ordering requirement
 * (we always end with the same final state: "row id X has been touched, drop its cache").
 *
 * <h3>At-least-once</h3>
 * Spring Kafka acks after the listener method returns successfully. If the pod dies
 * mid-DEL, the offset isn't committed and the next replica re-processes the event;
 * because {@code DEL} is idempotent (no-op on a missing key), this is safe.
 */
@Component
public class CdcEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(CdcEventConsumer.class);

    private final ObjectMapper mapper;
    private final CacheInvalidator invalidator;
    private final Counter handled;
    private final Counter skippedNoPk;
    private final Counter skippedTombstone;

    public CdcEventConsumer(ObjectMapper mapper,
                            CacheInvalidator invalidator,
                            MeterRegistry registry) {
        this.mapper             = mapper;
        this.invalidator        = invalidator;
        this.handled            = Counter.builder("invalidator.events.handled").register(registry);
        this.skippedNoPk        = Counter.builder("invalidator.events.skipped").tag("reason", "no_pk").register(registry);
        this.skippedTombstone   = Counter.builder("invalidator.events.skipped").tag("reason", "tombstone").register(registry);
    }

    @KafkaListener(
            topics            = "${app.kafka.topic}",
            groupId           = "${app.kafka.group}",
            containerFactory  = "kafkaListenerContainerFactory")
    public void onCdcEvent(ConsumerRecord<String, String> rec) {
        if (rec.value() == null) {
            // Debezium emits a tombstone (null value) right after a delete — nothing to do
            // for cache invalidation since we already handled the preceding "d" event.
            skippedTombstone.increment();
            return;
        }

        DebeziumEnvelope.parse(rec.value(), mapper).ifPresentOrElse(env -> {
            env.primaryKey("id").ifPresentOrElse(id -> {
                invalidator.invalidate(id);
                handled.increment();
                LOG.debug("op={} id={} partition={} offset={}",
                        env.op(), id, rec.partition(), rec.offset());
            }, () -> {
                skippedNoPk.increment();
                LOG.warn("CDC event without id pk; partition={} offset={}",
                        rec.partition(), rec.offset());
            });
        }, () -> {
            skippedNoPk.increment();
            LOG.warn("unparseable CDC envelope; partition={} offset={}",
                    rec.partition(), rec.offset());
        });
    }
}
