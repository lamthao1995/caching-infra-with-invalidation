package io.github.lamthao1995.cache.reader.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lamthao1995.cache.common.cache.CacheKeys;
import io.github.lamthao1995.cache.common.model.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache layer for the reader. Wraps the raw Redis ops behind a typed API so the
 * controller stays trivial: {@link #get(long)} returns an {@code Optional<Item>}, and
 * {@link #put(Item)} swallows serialisation errors so a misbehaving cache can never take
 * the read path down — we just degrade to MySQL-only.
 *
 * <p>Cache value is the same JSON that the writer would have produced. We deliberately
 * do not version cache entries beyond the {@code v1:} key namespace; if the {@link Item}
 * record adds a field, bump the namespace in {@link CacheKeys} to flush every entry in a
 * single deploy.</p>
 */
@Component
public class ItemCache {

    private static final Logger LOG = LoggerFactory.getLogger(ItemCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public ItemCache(StringRedisTemplate redis,
                     ObjectMapper mapper,
                     @Value("${app.cache.ttl}") Duration ttl) {
        this.redis  = redis;
        this.mapper = mapper;
        this.ttl    = ttl;
    }

    public Optional<Item> get(long id) {
        String key = CacheKeys.itemById(id);
        String json;
        try {
            json = redis.opsForValue().get(key);
        } catch (Exception ex) {
            // Redis unavailable → behave like a cache miss so reads keep flowing from MySQL.
            LOG.warn("redis GET failed for {}: {}", key, ex.toString());
            return Optional.empty();
        }
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, Item.class));
        } catch (Exception ex) {
            // Stale shape from an older deploy — treat as a miss and let put() overwrite.
            LOG.warn("cache deser failed for {}: {}", key, ex.toString());
            return Optional.empty();
        }
    }

    public void put(Item item) {
        if (item == null || item.id() == null) return;
        String key = CacheKeys.itemById(item.id());
        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(item), ttl);
        } catch (Exception ex) {
            LOG.warn("redis SETEX failed for {}: {}", key, ex.toString());
        }
    }
}
