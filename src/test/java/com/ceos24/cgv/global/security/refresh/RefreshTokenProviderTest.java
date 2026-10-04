package com.ceos24.cgv.global.security.refresh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenProviderTest {

    private final RefreshTokenProvider provider =
            new RefreshTokenProvider(new RefreshTokenProperties(Duration.ofDays(14)));

    @Test
    @DisplayName("리프레시 토큰은 256비트 난수를 패딩 없는 Base64URL로 인코딩한 43자다")
    void 리프레시_토큰은_43자_Base64URL이다() {
        String token = provider.generate();

        assertThat(token).hasSize(43).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    @DisplayName("발급한 토큰과 길이가 같을 때만 발급 길이로 본다")
    void 발급_길이는_43자뿐이다() {
        assertThat(provider.hasIssuedLength(provider.generate())).isTrue();
        assertThat(provider.hasIssuedLength("a".repeat(42))).isFalse();
        assertThat(provider.hasIssuedLength("a".repeat(44))).isFalse();
    }

    // JWT는 점 두 개로 나뉜 세 조각이어야 한다. 점이 없으면 액세스 토큰 자리에 들어가도 형식 오류로 떨어진다.
    @Test
    @DisplayName("리프레시 토큰에는 점이 없어 JWT 형식이 될 수 없다")
    void 리프레시_토큰에는_점이_없다() {
        IntStream.range(0, 1000).forEach(i -> assertThat(provider.generate()).doesNotContain("."));
    }

    @Test
    @DisplayName("1만 개를 만들어도 중복이 없다")
    void 만_개를_만들어도_중복이_없다() {
        Set<String> tokens = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> tokens.add(provider.generate()));

        assertThat(tokens).hasSize(10_000);
    }

    @Test
    @DisplayName("해시는 SHA-256 hex 64자이고 같은 입력이면 항상 같다")
    void 해시는_결정적인_SHA256_hex다() {
        String token = provider.generate();

        assertThat(provider.hash(token)).hasSize(64).matches("^[0-9a-f]+$")
                .isEqualTo(provider.hash(token))
                .isNotEqualTo(token);
        // 알려진 값으로 알고리즘이 SHA-256인지 고정한다
        assertThat(provider.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("만료 시각은 발급 시각에 설정한 수명을 더한 값이다")
    void 만료_시각은_발급_시각_더하기_수명이다() {
        LocalDateTime issuedAt = LocalDateTime.of(2026, 1, 1, 0, 0);

        assertThat(provider.expiresAt(issuedAt)).isEqualTo(issuedAt.plusDays(14));
        assertThat(provider.getValiditySeconds()).isEqualTo(Duration.ofDays(14).toSeconds());
    }
}
