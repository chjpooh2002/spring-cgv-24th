package com.ceos24.cgv.global.security.refresh;

import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

// 리프레시 토큰은 JWT가 아니다. 폐기 여부를 어차피 DB에서 확인하므로 토큰 안에 정보를 담을 이유가 없고,
// JWT로 만들면 같은 키로 서명된 이상 액세스 토큰 필터를 통과할 위험이 생긴다.
@Component
public class RefreshTokenProvider {

    // 256비트. 추측으로 맞힐 확률이 서명키를 맞힐 확률과 같은 수준이다.
    private static final int TOKEN_BYTES = 32;

    // 운영체제 엔트로피를 쓰는 암호학적 난수 생성기. java.util.Random은 출력 몇 개로 다음 값을 예측할 수 있다.
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration validity;

    public RefreshTokenProvider(RefreshTokenProperties properties) {
        this.validity = properties.validity();
    }

    // Base64URL 알파벳에는 '.'이 없다. 점 두 개로 나뉘는 JWT 형식이 될 수 없어 액세스 토큰 자리에 넣으면 형식 오류다.
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 비밀번호와 달리 솔트 없는 빠른 해시를 쓴다. 입력이 사람이 고른 값이 아니라 256비트 난수라 사전 공격이 성립하지 않고,
    // 같은 입력이 항상 같은 해시가 되어야 받은 토큰으로 행을 조회할 수 있다.
    public String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // 모든 자바 구현은 SHA-256을 제공해야 하므로 도달하지 않는다.
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public LocalDateTime expiresAt(LocalDateTime issuedAt) {
        return issuedAt.plus(validity);
    }

    public long getValiditySeconds() {
        return validity.toSeconds();
    }
}
