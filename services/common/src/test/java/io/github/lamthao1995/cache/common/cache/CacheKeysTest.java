package io.github.lamthao1995.cache.common.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeysTest {

    @Test
    void item_key_is_namespaced_and_stable() {
        assertThat(CacheKeys.itemById(42)).isEqualTo("v1:item:42");
    }
}
