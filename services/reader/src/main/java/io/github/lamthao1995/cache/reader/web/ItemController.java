package io.github.lamthao1995.cache.reader.web;

import io.github.lamthao1995.cache.common.model.Item;
import io.github.lamthao1995.cache.reader.cache.ItemCache;
import io.github.lamthao1995.cache.reader.repo.ItemRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Cache-aside read API.
 *
 * <pre>
 *   GET /api/v1/items/{id}
 *     ├── Redis hit  → 200 + body, {@code X-Cache: HIT}
 *     ├── Redis miss → MySQL lookup
 *     │                  ├── found    → cache the row, return 200 + body, {@code X-Cache: MISS}
 *     │                  └── not found → 404, {@code X-Cache: MISS}
 *     └── Redis down → behaves like miss (see {@link ItemCache} swallow logic)
 * </pre>
 *
 * The {@code X-Cache} header is what the stress generator splits on; without it the load
 * report cannot tell hot reads (Redis) from cold reads (MySQL) and the whole experiment
 * is meaningless.
 *
 * <h2>Eventual consistency note</h2>
 * This is the textbook cache-aside read-then-write race:
 * <ol>
 *   <li>Reader misses Redis and starts loading row {@code N} from MySQL.</li>
 *   <li>Writer commits an UPDATE; Debezium publishes; invalidator DELs Redis key.</li>
 *   <li>Reader's {@code cache.put(...)} stores the now-stale snapshot back into Redis.</li>
 * </ol>
 * The stale value lives until {@code CACHE_TTL} expires. We accept this trade-off in
 * exchange for keeping the write path off the cache (no dual-write, no two-phase commit
 * to the cache + DB). If stronger freshness is required, options include:
 * <ul>
 *   <li>Cache the row's {@code updated_at} alongside the value and discard cache writes
 *       whose timestamp is older than the version Redis already holds.</li>
 *   <li>Issue a delayed second {@code DEL} from the invalidator (the so-called
 *       "Facebook double-delete"), trading a tiny extra DB hit for closing the window.</li>
 *   <li>Use Redis transactions (MULTI/WATCH) keyed by {@code updated_at} when writing back.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemRepository repo;
    private final ItemCache cache;
    private final Counter hits;
    private final Counter misses;
    private final Counter notFound;

    public ItemController(ItemRepository repo, ItemCache cache, MeterRegistry registry) {
        this.repo     = repo;
        this.cache    = cache;
        this.hits     = Counter.builder("reader.cache").tag("outcome", "hit").register(registry);
        this.misses   = Counter.builder("reader.cache").tag("outcome", "miss").register(registry);
        this.notFound = Counter.builder("reader.cache").tag("outcome", "not_found").register(registry);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable long id) {
        Optional<Item> hit = cache.get(id);
        if (hit.isPresent()) {
            hits.increment();
            return ResponseEntity.ok().header("X-Cache", "HIT").body(hit.get());
        }
        Optional<Item> fromDb = repo.findById(id);
        if (fromDb.isEmpty()) {
            notFound.increment();
            return ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .header("X-Cache", "MISS")
                    .body(Map.of("error", "not_found", "id", id));
        }
        cache.put(fromDb.get());
        misses.increment();
        return ResponseEntity.ok().header("X-Cache", "MISS").body(fromDb.get());
    }
}
