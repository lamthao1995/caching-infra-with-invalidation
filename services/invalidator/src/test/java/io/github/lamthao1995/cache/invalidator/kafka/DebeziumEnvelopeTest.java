package io.github.lamthao1995.cache.invalidator.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DebeziumEnvelopeTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void parses_create_envelope_and_returns_after_id() {
        String json = """
            { "schema": {}, "payload": {
                "before": null,
                "after":  { "id": 42, "name": "x" },
                "op": "c",
                "ts_ms": 123
            } }""";
        Optional<DebeziumEnvelope> env = DebeziumEnvelope.parse(json, M);
        assertThat(env).isPresent();
        assertThat(env.get().op()).isEqualTo(DebeziumEnvelope.Op.CREATE);
        assertThat(env.get().primaryKey("id")).contains(42L);
    }

    @Test
    void parses_update_envelope() {
        String json = """
            { "payload": {
                "before": { "id": 7, "name": "old" },
                "after":  { "id": 7, "name": "new" },
                "op": "u"
            } }""";
        DebeziumEnvelope env = DebeziumEnvelope.parse(json, M).orElseThrow();
        assertThat(env.op()).isEqualTo(DebeziumEnvelope.Op.UPDATE);
        assertThat(env.primaryKey("id")).contains(7L);
    }

    @Test
    void delete_uses_before_image_for_pk() {
        String json = """
            { "payload": {
                "before": { "id": 99, "name": "gone" },
                "after":  null,
                "op": "d"
            } }""";
        DebeziumEnvelope env = DebeziumEnvelope.parse(json, M).orElseThrow();
        assertThat(env.op()).isEqualTo(DebeziumEnvelope.Op.DELETE);
        assertThat(env.primaryKey("id")).contains(99L);
    }

    @Test
    void snapshot_read_event_is_treated_like_create() {
        String json = """
            { "payload": {
                "before": null,
                "after":  { "id": 11 },
                "op": "r"
            } }""";
        DebeziumEnvelope env = DebeziumEnvelope.parse(json, M).orElseThrow();
        assertThat(env.op()).isEqualTo(DebeziumEnvelope.Op.READ);
        assertThat(env.primaryKey("id")).contains(11L);
    }

    @Test
    void unparseable_payload_returns_empty() {
        assertThat(DebeziumEnvelope.parse("not json", M)).isEmpty();
        assertThat(DebeziumEnvelope.parse("", M)).isEmpty();
        assertThat(DebeziumEnvelope.parse(null, M)).isEmpty();
    }

    @Test
    void missing_or_non_numeric_pk_returns_empty_optional() {
        String json = """
            { "payload": {
                "after":  { "name": "no-id-here" },
                "op": "c"
            } }""";
        DebeziumEnvelope env = DebeziumEnvelope.parse(json, M).orElseThrow();
        assertThat(env.primaryKey("id")).isEmpty();
    }
}
