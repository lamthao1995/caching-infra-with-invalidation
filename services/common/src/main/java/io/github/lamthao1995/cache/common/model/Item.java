package io.github.lamthao1995.cache.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire + storage type for the {@code catalog.items} table.
 *
 * <p>Kept as a Java record so it serialises/deserialises cleanly via Jackson and stays
 * trivially equality-comparable in tests. {@code createdAt} / {@code updatedAt} are
 * populated by MySQL ({@code DEFAULT CURRENT_TIMESTAMP}) on insert/update.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Item(
        Long id,
        String name,
        String description,
        long priceCents,
        String currency,
        Integer stock,
        Instant createdAt,
        Instant updatedAt
) {
    public static final int MAX_NAME_LEN = 200;
    public static final int MAX_DESCRIPTION_LEN = 2000;

    public Item withId(long newId) {
        return new Item(newId, name, description, priceCents, currency, stock, createdAt, updatedAt);
    }

    public Item withTimestamps(Instant created, Instant updated) {
        return new Item(id, name, description, priceCents, currency, stock, created, updated);
    }
}
