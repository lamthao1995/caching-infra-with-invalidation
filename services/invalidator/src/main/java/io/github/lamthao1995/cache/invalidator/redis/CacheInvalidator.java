package io.github.lamthao1995.cache.invalidator.redis;

import io.github.lamthao1995.cache.common.cache.CacheKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Single point that knows how to delete a cache entry. Kept tiny and dependency-free
 * (just {@link StringRedisTemplate}) so it's trivially testable with the embedded Redis
 * spinner in {@code CdcEventConsumerTest}.
 *
 * <p>Idempotency note: {@code DEL} on a missing key is a no-op in Redis, so re-delivery
 * from Kafka can never corrupt the cache. This is also why we don't need to track which
 * events we've already processed — we just blow the entry away every time.</p>
 */
@Component
public class CacheInvalidator {

    private static final Logger LOG = LoggerFactory.getLogger(CacheInvalidator.class);

    private final StringRedisTemplate redis;

    public CacheInvalidator(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void invalidate(long itemId) {
        String key = CacheKeys.itemById(itemId);
        Boolean deleted = redis.delete(key);
        if (Boolean.TRUE.equals(deleted)) {
            LOG.debug("invalidated cache key={}", key);
        } else {
            LOG.trace("cache key already absent (no-op DEL): {}", key);
        }
    }
}
