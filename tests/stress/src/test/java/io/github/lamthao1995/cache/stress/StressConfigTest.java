package io.github.lamthao1995.cache.stress;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StressConfigTest {

    @Test
    void parses_eq_and_space_flags() {
        StressConfig cfg = StressConfig.fromArgs(new String[]{
                "--rps=5000",
                "--duration", "30s",
                "--read-ratio=0.5",
                "--update-ratio=0.2",
                "--workers=64"
        });
        assertThat(cfg.rps()).isEqualTo(5000);
        assertThat(cfg.duration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(cfg.readRatio()).isEqualTo(0.5);
        assertThat(cfg.updateRatio()).isEqualTo(0.2);
        assertThat(cfg.workers()).isEqualTo(64);
    }

    @Test
    void duration_supports_ms_s_m_h() {
        assertThat(StressConfig.parseDuration("250ms")).isEqualTo(Duration.ofMillis(250));
        assertThat(StressConfig.parseDuration("30s")).isEqualTo(Duration.ofSeconds(30));
        assertThat(StressConfig.parseDuration("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(StressConfig.parseDuration("2h")).isEqualTo(Duration.ofHours(2));
    }

    @Test
    void validate_rejects_oversized_mix() {
        StressConfig bad = StressConfig.fromArgs(new String[]{
                "--read-ratio=0.8",
                "--update-ratio=0.5"
        });
        assertThatThrownBy(bad::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be <= 1");
    }

    @Test
    void validate_rejects_zero_workers() {
        StressConfig bad = StressConfig.fromArgs(new String[]{"--workers=0"});
        assertThatThrownBy(bad::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workers");
    }

    @Test
    void unknown_token_is_rejected() {
        assertThatThrownBy(() -> StressConfig.fromArgs(new String[]{"oops"}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
