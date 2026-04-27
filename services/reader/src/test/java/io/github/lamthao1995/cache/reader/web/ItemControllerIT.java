package io.github.lamthao1995.cache.reader.web;

import io.github.lamthao1995.cache.common.model.Item;
import io.github.lamthao1995.cache.reader.cache.ItemCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end through Spring Boot's Web layer with H2 (MySQL-compat) for the DB and a
 * mocked {@link ItemCache} for Redis. We assert the exact behaviour the stress generator
 * relies on: hit / miss is reported in {@code X-Cache}, and a miss writes back into the
 * cache before responding.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ItemControllerIT {

    @Autowired MockMvc mvc;
    @MockBean ItemCache cache;

    @Test
    void hit_path_serves_from_cache_and_does_not_touch_db_writeback() throws Exception {
        Item cached = new Item(1L, "from-cache", "x", 100, "USD", 0, null, null);
        when(cache.get(1L)).thenReturn(Optional.of(cached));

        mvc.perform(get("/api/v1/items/1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"))
                .andExpect(jsonPath("$.name").value("from-cache"));

        verify(cache).get(1L);
        verify(cache, never()).put(any(Item.class));
    }

    @Test
    void miss_loads_from_db_and_writes_back_to_cache() throws Exception {
        when(cache.get(2L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/items/2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.name").value("gadget"));

        verify(cache).put(argThat(item -> item != null && item.id() != null && item.id() == 2L));
    }

    @Test
    void unknown_id_returns_404_with_miss_header() throws Exception {
        when(cache.get(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/items/999"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Cache", "MISS"));

        verify(cache, never()).put(any(Item.class));
    }
}
