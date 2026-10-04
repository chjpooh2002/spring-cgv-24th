package com.ceos24.cgv.global.security.jwt;

import com.ceos24.cgv.domain.user.entity.Role;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.global.security.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtProviderTest {

    private static final String SECRET = "Y2d2LXRlc3Qtb25seS1kdW1teS1oczI1Ni1rZXktbm90LWZvci1wcm9kdWN0aW9uISE=";
    private static final String OTHER_SECRET = "b3RoZXItc2VydmVyLWtleS10aGF0LWlzLWFsc28tMjU2LWJpdHMtbG9uZyEh";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration VALIDITY = Duration.ofMinutes(30);

    private final JwtProvider provider = providerAt(NOW, SECRET);

    @Test
    void 발급한_토큰을_검증하면_사용자_id와_권한이_나온다() {
        String token = provider.createAccessToken(42L, Role.ADMIN);

        AuthUser authUser = provider.parse(token);

        assertThat(authUser.userId()).isEqualTo(42L);
        assertThat(authUser.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void 토큰에는_식별자_권한_시각_발급자만_들어간다() {
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(provider.createAccessToken(42L, Role.USER))
                .getPayload();

        assertThat(claims.keySet()).containsExactlyInAnyOrder("sub", "role", "iat", "exp", "iss");
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.get("role")).isEqualTo("USER");
        assertThat(claims.getIssuer()).isEqualTo("cgv-api");
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(VALIDITY));
    }

    @Test
    void 만료_시각이_지나면_TOKEN_EXPIRED() {
        String token = provider.createAccessToken(42L, Role.USER);
        JwtProvider later = providerAt(NOW.plus(VALIDITY).plusSeconds(1), SECRET);

        assertError(() -> later.parse(token), ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 만료_직전까지는_통과한다() {
        String token = provider.createAccessToken(42L, Role.USER);
        JwtProvider justBefore = providerAt(NOW.plus(VALIDITY).minusSeconds(1), SECRET);

        assertThat(justBefore.parse(token).userId()).isEqualTo(42L);
    }

    @Test
    void payload를_바꾸면_서명이_맞지_않아_TOKEN_INVALID() {
        String token = provider.createAccessToken(42L, Role.USER);

        String forged = replacePayload(token, "\"USER\"", "\"ADMIN\"");

        assertError(() -> provider.parse(forged), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 다른_키로_서명한_토큰은_TOKEN_INVALID() {
        String token = providerAt(NOW, OTHER_SECRET).createAccessToken(42L, Role.ADMIN);

        assertError(() -> provider.parse(token), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 서명하지_않은_alg_none_토큰은_TOKEN_INVALID() {
        String header = base64Url("{\"alg\":\"none\"}");
        String payload = base64Url("{\"sub\":\"1\",\"role\":\"ADMIN\",\"iss\":\"cgv-api\",\"exp\":%d}"
                .formatted(NOW.plus(VALIDITY).getEpochSecond()));

        assertError(() -> provider.parse(header + "." + payload + "."), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 허용하지_않은_알고리즘으로_서명한_토큰은_TOKEN_INVALID() {
        String token = Jwts.builder()
                .subject("42").claim("role", "ADMIN").issuer("cgv-api")
                .expiration(Date.from(NOW.plus(VALIDITY)))
                .signWith(Keys.hmacShaKeyFor(new byte[64]), Jwts.SIG.HS512)
                .compact();

        assertError(() -> provider.parse(token), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 발급자가_다르면_TOKEN_INVALID() {
        String token = Jwts.builder()
                .subject("42").claim("role", "USER").issuer("other-service")
                .expiration(Date.from(NOW.plus(VALIDITY)))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)), Jwts.SIG.HS256)
                .compact();

        assertError(() -> provider.parse(token), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 만료된_토큰을_변조하면_만료가_아니라_TOKEN_INVALID() {
        String forged = replacePayload(provider.createAccessToken(42L, Role.USER), "\"USER\"", "\"ADMIN\"");
        JwtProvider later = providerAt(NOW.plus(VALIDITY).plusSeconds(1), SECRET);

        assertError(() -> later.parse(forged), ErrorCode.TOKEN_INVALID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-a-jwt", "only.two", "!!!.@@@.###"})
    void JWT_형식이_아니면_TOKEN_INVALID(String token) {
        assertError(() -> provider.parse(token), ErrorCode.TOKEN_INVALID);
    }

    @Test
    void 서명키가_256비트보다_짧으면_설정_이름을_담은_예외로_생성할_수_없다() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[31]);

        assertThatThrownBy(() -> providerAt(NOW, shortSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret")
                .hasCauseInstanceOf(WeakKeyException.class);
    }

    @Test
    void 서명키가_Base64가_아니면_설정_이름을_담은_예외로_생성할_수_없다() {
        String notBase64 = "이건-base64가-아니다!!";

        assertThatThrownBy(() -> providerAt(NOW, notBase64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret")
                .hasMessageNotContaining(notBase64)
                .hasCauseInstanceOf(DecodingException.class);
    }

    private static JwtProvider providerAt(Instant now, String secret) {
        return new JwtProvider(new JwtProperties(secret, VALIDITY), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static void assertError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    private static String replacePayload(String token, String from, String to) {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return parts[0] + "." + base64Url(payload.replace(from, to)) + "." + parts[2];
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
