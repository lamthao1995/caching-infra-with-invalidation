package io.github.lamthao1995.cache.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItemValidatorTest {

    private static Item ok() {
        return new Item(null, "thing", "desc", 1500, "USD", 10, null, null);
    }

    @Test
    void valid_payload_returns_no_errors() {
        assertThat(ItemValidator.validate(ok())).isEmpty();
    }

    @Test
    void null_body_is_rejected() {
        assertThat(ItemValidator.validate(null)).contains("body is required");
    }

    @Test
    void name_must_be_present_and_bounded() {
        assertThat(ItemValidator.validate(new Item(null, "", "d", 1, "USD", 1, null, null)))
                .contains("name is required");
        String tooLong = "x".repeat(Item.MAX_NAME_LEN + 1);
        assertThat(ItemValidator.validate(new Item(null, tooLong, "d", 1, "USD", 1, null, null)))
                .anySatisfy(msg -> assertThat(msg).contains("name exceeds"));
    }

    @Test
    void price_and_stock_must_be_non_negative() {
        assertThat(ItemValidator.validate(new Item(null, "t", "d", -1, "USD", 0, null, null)))
                .contains("priceCents must be >= 0");
        assertThat(ItemValidator.validate(new Item(null, "t", "d", 0, "USD", -3, null, null)))
                .contains("stock must be >= 0");
    }

    @Test
    void currency_must_be_in_whitelist() {
        assertThat(ItemValidator.validate(new Item(null, "t", "d", 0, "XXX", 0, null, null)))
                .anySatisfy(msg -> assertThat(msg).startsWith("currency must be one of"));
    }
}
