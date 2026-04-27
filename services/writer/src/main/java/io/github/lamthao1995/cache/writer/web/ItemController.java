package io.github.lamthao1995.cache.writer.web;

import io.github.lamthao1995.cache.common.model.Item;
import io.github.lamthao1995.cache.common.model.ItemValidator;
import io.github.lamthao1995.cache.writer.repo.ItemRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Write path of the catalog: every mutation goes to MySQL first. Reads here are intentionally
 * bare ({@code GET /items/{id}} returns the freshly written row) so writers can verify
 * round-trips without going through the cache. The "real" read API lives in the reader service.
 */
@RestController
@RequestMapping("/api/v1/items")
public class ItemController {

    private final ItemRepository repo;

    public ItemController(ItemRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Item body) {
        List<String> errors = ItemValidator.validate(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
        Item saved = repo.insert(body);
        return ResponseEntity.created(URI.create("/api/v1/items/" + saved.id())).body(saved);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable long id) {
        return repo.findById(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "not_found", "id", id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody(required = false) Item body) {
        List<String> errors = ItemValidator.validate(body);
        if (!errors.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
        if (!repo.update(id, body)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "not_found", "id", id));
        }
        return ResponseEntity.ok(repo.findById(id).orElseThrow());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!repo.delete(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "not_found", "id", id));
        }
        return ResponseEntity.noContent().build();
    }
}
