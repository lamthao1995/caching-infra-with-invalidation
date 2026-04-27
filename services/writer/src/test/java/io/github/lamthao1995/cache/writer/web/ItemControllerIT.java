package io.github.lamthao1995.cache.writer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lamthao1995.cache.common.model.Item;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Boots the writer with H2 in MySQL-compat mode and exercises the full HTTP CRUD round trip.
 * No real MySQL needed — Testcontainers is reserved for the invalidator (which actually
 * speaks the binlog protocol and so cannot run against H2).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ItemControllerIT {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void crud_round_trip() throws Exception {
        Item create = new Item(null, "widget", "shiny", 1999, "USD", 7, null, null);

        MvcResult posted = mvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("widget"))
                .andReturn();

        Item saved = mapper.readValue(posted.getResponse().getContentAsByteArray(), Item.class);
        assertThat(saved.id()).isNotNull();

        mvc.perform(get("/api/v1/items/" + saved.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price_cents").value(1999));

        Item patch = new Item(null, "widget", "shinier", 2999, "USD", 6, null, null);
        mvc.perform(put("/api/v1/items/" + saved.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(patch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price_cents").value(2999));

        mvc.perform(delete("/api/v1/items/" + saved.id()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/items/" + saved.id()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejects_invalid_payload() throws Exception {
        Item bad = new Item(null, "", "d", -1, "XYZ", -1, null, null);
        mvc.perform(post("/api/v1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }
}
