package io.github.lamthao1995.cache.invalidator.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CacheInvalidatorTest {

    @Test
    void deletes_with_namespaced_key() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete(eq("v1:item:42"))).thenReturn(true);

        new CacheInvalidator(redis).invalidate(42L);

        verify(redis).delete("v1:item:42");
        verifyNoMoreInteractions(redis);
    }

    @Test
    void treats_missing_key_as_a_silent_no_op() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.delete(any(String.class))).thenReturn(false);

        // No exception, no second DEL — caller must not need to know whether the key was there.
        new CacheInvalidator(redis).invalidate(7L);

        assertThat(true).isTrue();
        verify(redis).delete("v1:item:7");
    }
}
