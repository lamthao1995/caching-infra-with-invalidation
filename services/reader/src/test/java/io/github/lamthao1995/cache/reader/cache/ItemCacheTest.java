package io.github.lamthao1995.cache.reader.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lamthao1995.cache.common.model.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ItemCacheTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private ItemCache cache;

    @BeforeEach
    void setup() {
        redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> mockOps = mock(ValueOperations.class);
        ops = mockOps;
        when(redis.opsForValue()).thenReturn(ops);
        cache = new ItemCache(redis, new ObjectMapper(), Duration.ofMinutes(10));
    }

    @Test
    void get_returns_empty_when_redis_returns_null() {
        when(ops.get("v1:item:1")).thenReturn(null);
        assertThat(cache.get(1L)).isEmpty();
    }

    @Test
    void get_deserialises_json_back_into_item() {
        when(ops.get("v1:item:1")).thenReturn(
                "{\"id\":1,\"name\":\"x\",\"priceCents\":100,\"currency\":\"USD\"}");
        Optional<Item> got = cache.get(1L);
        assertThat(got).isPresent();
        assertThat(got.get().id()).isEqualTo(1L);
        assertThat(got.get().priceCents()).isEqualTo(100);
    }

    @Test
    void get_treats_redis_failure_as_miss() {
        when(ops.get(any(String.class))).thenThrow(new RuntimeException("boom"));
        assertThat(cache.get(1L)).isEmpty();
    }

    @Test
    void get_treats_corrupt_payload_as_miss() {
        when(ops.get("v1:item:1")).thenReturn("not json");
        assertThat(cache.get(1L)).isEmpty();
    }

    @Test
    void put_writes_with_ttl_and_namespaced_key() {
        Item item = new Item(42L, "x", null, 100, "USD", 0, null, null);
        cache.put(item);
        verify(ops).set(eq("v1:item:42"), any(String.class), eq(Duration.ofMinutes(10)));
    }

    @Test
    void put_no_ops_when_id_is_null() {
        Item item = new Item(null, "x", null, 100, "USD", 0, null, null);
        cache.put(item);
        verifyNoInteractions(ops);
    }

    @Test
    void put_swallows_redis_failures() {
        Item item = new Item(7L, "x", null, 100, "USD", 0, null, null);
        doThrow(new RuntimeException("boom")).when(ops).set(any(), any(), any(Duration.class));
        cache.put(item);   // must not throw
    }
}
