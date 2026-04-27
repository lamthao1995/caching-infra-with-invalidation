package io.github.lamthao1995.cache.reader.repo;

import io.github.lamthao1995.cache.common.model.Item;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Read-only access to {@code catalog.items}. Mirrors the writer's {@link RowMapper} so
 * the JSON returned to clients on cache miss is byte-identical to what the writer wrote
 * — ensuring the cached entry doesn't drift in shape from the persistent record.
 */
@Repository
public class ItemRepository {

    private static final RowMapper<Item> ROW_MAPPER = (rs, rn) -> new Item(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getLong("price_cents"),
            rs.getString("currency"),
            (Integer) rs.getObject("stock"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcTemplate jdbc;

    public ItemRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Item> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, name, description, price_cents, currency, stock, "
                            + "created_at, updated_at FROM items WHERE id = ?",
                    ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
