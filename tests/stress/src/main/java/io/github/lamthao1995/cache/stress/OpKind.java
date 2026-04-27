package io.github.lamthao1995.cache.stress;

/**
 * Operation buckets reported separately. Reads are split into HIT / MISS based on the
 * {@code X-Cache} response header set by the reader service — this is the whole point
 * of the experiment, so we never collapse them into a single "read" line.
 */
public enum OpKind {
    WRITE,        // POST /api/v1/items
    UPDATE,       // PUT  /api/v1/items/{id}     -> triggers binlog -> cache invalidate
    READ_HIT,     // GET  /api/v1/items/{id}     served from Redis
    READ_MISS,    // GET  /api/v1/items/{id}     served from MySQL, then cached
    ERROR         // catch-all for non-2xx / network failures
}
