package io.github.lamthao1995.cache.common.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure-function validator for {@link Item} payloads on the write path.
 *
 * <p>Centralised here (instead of using bean-validation annotations on the record) so the
 * exact same rules can be reused by both the writer service's HTTP handler and any future
 * batch/import job without dragging in a {@code Validator} bean.</p>
 */
public final class ItemValidator {

    private static final Set<String> ALLOWED_CURRENCIES = Set.of("USD", "EUR", "VND", "JPY", "GBP");

    private ItemValidator() {
    }

    /** Returns the list of validation errors; empty list means the payload is valid. */
    public static List<String> validate(Item item) {
        List<String> errors = new ArrayList<>();
        if (item == null) {
            errors.add("body is required");
            return errors;
        }
        if (item.name() == null || item.name().isBlank()) {
            errors.add("name is required");
        } else if (item.name().length() > Item.MAX_NAME_LEN) {
            errors.add("name exceeds " + Item.MAX_NAME_LEN + " chars");
        }
        if (item.description() != null && item.description().length() > Item.MAX_DESCRIPTION_LEN) {
            errors.add("description exceeds " + Item.MAX_DESCRIPTION_LEN + " chars");
        }
        if (item.priceCents() < 0) {
            errors.add("priceCents must be >= 0");
        }
        if (item.currency() == null || !ALLOWED_CURRENCIES.contains(item.currency())) {
            errors.add("currency must be one of " + ALLOWED_CURRENCIES);
        }
        if (item.stock() != null && item.stock() < 0) {
            errors.add("stock must be >= 0");
        }
        return errors;
    }

    public static boolean isValid(Item item) {
        return validate(item).isEmpty();
    }
}
