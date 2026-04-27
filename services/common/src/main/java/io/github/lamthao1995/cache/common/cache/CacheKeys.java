package io.github.lamthao1995.cache.common.cache;

/**
 * Single source of truth for Redis key naming. Both the reader (which writes the cache)
 * and the invalidator (which deletes from it on binlog events) must agree on this format,
 * so we keep it in {@code common} and reference it from both modules.
 */
public final class CacheKeys {

    /** Prefix used for every cache entry; bumping it is a cheap way to flush all keys. */
    public static final String NAMESPACE = "v1";

    private CacheKeys() {
    }

    /** Cached JSON for a single item by primary key. */
    public static String itemById(long id) {
        return NAMESPACE + ":item:" + id;
    }
}
