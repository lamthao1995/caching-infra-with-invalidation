package io.github.lamthao1995.cache.invalidator.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Tiny parser for the Debezium envelope JSON. We deliberately do not bind to a generated
 * record class because:
 *
 * <ul>
 *   <li>Schema evolution is then a code change instead of a config change.</li>
 *   <li>The only field we care about for invalidation is the primary key, which we can
 *       pluck directly from the {@code payload.before} / {@code payload.after} sub-tree.</li>
 *   <li>Avoids dragging the full Avro / schema-registry dependency stack in.</li>
 * </ul>
 *
 * <p>Operation codes per Debezium docs: {@code c}=create, {@code u}=update, {@code d}=delete,
 * {@code r}=read (initial snapshot), {@code t}=truncate. We treat all of c/u/r/d as
 * "row touched, blow the cache" — only {@code t} is a no-op (table truncates are rare,
 * and our cache is already keyed per-row).</p>
 */
public final class DebeziumEnvelope {

    public enum Op { CREATE, UPDATE, DELETE, READ, TRUNCATE, UNKNOWN }

    private final Op op;
    private final JsonNode beforeOrAfter;

    private DebeziumEnvelope(Op op, JsonNode beforeOrAfter) {
        this.op = op;
        this.beforeOrAfter = beforeOrAfter;
    }

    public Op op() {
        return op;
    }

    public Optional<Long> primaryKey(String column) {
        if (beforeOrAfter == null || beforeOrAfter.isMissingNode() || beforeOrAfter.isNull()) {
            return Optional.empty();
        }
        JsonNode pk = beforeOrAfter.path(column);
        return pk.isNumber() ? Optional.of(pk.asLong()) : Optional.empty();
    }

    /**
     * Parse a Debezium envelope JSON string. Returns an empty optional if the message is
     * a tombstone ({@code value == null}) or otherwise unparseable — tombstones are
     * emitted by Debezium right after a delete event and carry no useful data, so we
     * intentionally skip them.
     */
    public static Optional<DebeziumEnvelope> parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            String opCode = payload.path("op").asText("");
            Op op = switch (opCode) {
                case "c" -> Op.CREATE;
                case "u" -> Op.UPDATE;
                case "d" -> Op.DELETE;
                case "r" -> Op.READ;
                case "t" -> Op.TRUNCATE;
                default  -> Op.UNKNOWN;
            };
            JsonNode body = (op == Op.DELETE) ? payload.path("before") : payload.path("after");
            return Optional.of(new DebeziumEnvelope(op, body));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }
}
