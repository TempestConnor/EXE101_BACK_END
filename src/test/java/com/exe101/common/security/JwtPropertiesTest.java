package com.exe101.common.security;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {
    static Stream<Duration> nonPositive() {
        return Stream.of(Duration.ZERO, Duration.ofMinutes(-1), null);
    }

    @ParameterizedTest
    @MethodSource("nonPositive")
    void non_positive_lifetime_ko(Duration lifetime) {
        // given / when / then
        // Refused when the application starts, rather than issuing tokens that are already expired.
        assertThatThrownBy(() -> new JwtProperties(lifetime)).isInstanceOf(IllegalStateException.class);
    }
}
