package io.github.lamthao1995.cache.writer.repo;

import io.github.lamthao1995.cache.common.model.Item;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Thin JdbcTemplate-backed CRUD for the {@code catalog.items} table.
 *
 * <p>The writer is intentionally the only path that mutates MySQL; the cache invalidator
 * never writes here. Every mutating method is {@link Transactional} so a failed insert
 * cannot leave the DB and the (eventual) binlog stream out of sync.</p>
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

    @Transactional
    public Item insert(Item item) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            // Request the `id` column by name (not Statement.RETURN_GENERATED_KEYS): some
            // JDBC drivers — H2's MySQL-compat mode and certain MySQL connector versions —
            // surface every column with a default-on-insert (id, created_at, updated_at) as
            // generated, which then breaks `keys.getKey()` ("multiple keys returned").
            // Asking for a single column makes the result deterministic across drivers.
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO items (name, description, price_cents, currency, stock) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, item.name());
            ps.setString(2, item.description());
            ps.setLong(3, item.priceCents());
            ps.setString(4, item.currency());
            if (item.stock() == null) {
                ps.setNull(5, java.sql.Types.INTEGER);
            } else {
                ps.setInt(5, item.stock());
            }
            return ps;
        }, keys);
        long id = Objects.requireNonNull(keys.getKey()).longValue();
        return findById(id).orElseThrow(() ->
                new IllegalStateException("freshly inserted row " + id + " not visible"));
    }

    public Optional<Item> findById(long id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, name, description, price_cents, currency, stock, created_at, updated_at "
                            + "FROM items WHERE id = ?",
                    ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** Returns true if a row was actually updated. */
    @Transactional
    public boolean update(long id, Item patch) {
        int n = jdbc.update(
                "UPDATE items SET name = ?, description = ?, price_cents = ?, currency = ?, stock = ? "
                        + "WHERE id = ?",
                patch.name(), patch.description(), patch.priceCents(), patch.currency(),
                patch.stock(), id);
        return n > 0;
    }

    @Transactional
    public boolean delete(long id) {
        return jdbc.update("DELETE FROM items WHERE id = ?", id) > 0;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
