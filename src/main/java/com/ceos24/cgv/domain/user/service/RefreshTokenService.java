package com.ceos24.cgv.domain.user.service;

import com.ceos24.cgv.domain.user.dto.TokenResponse;
import com.ceos24.cgv.domain.user.entity.RefreshToken;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.domain.user.repository.RefreshTokenRepository;
import com.ceos24.cgv.domain.user.repository.UserRepository;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.global.security.refresh.RefreshTokenProvider;
import com.ceos24.cgv.global.security.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// 리프레시 토큰의 DB 상태를 바꾸는 트랜잭션 경계. AuthService가 이 경계 바깥에서 잠금 대기 초과를 응답으로 바꾼다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenProvider refreshTokenProvider;
    private final JwtProvider jwtProvider;
    private final Clock clock;

    // 원문은 응답으로 한 번만 내보내고 DB에는 해시만 남긴다. 로그인마다 새 묶음이라 기기별 토큰이 따로 산다.
    @Transactional
    public String issue(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        deleteExpired(userId, now);

        String rawToken = refreshTokenProvider.generate();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(userRepository.getReferenceById(userId))
                .tokenHash(refreshTokenProvider.hash(rawToken))
                .familyId(UUID.randomUUID().toString())
                .expiresAt(refreshTokenProvider.expiresAt(now))
                .build());
        return rawToken;
    }

    // 순환할 때마다 행이 늘어 정리가 없으면 테이블이 계속 커진다. 스케줄러 대신 로그인할 때 그 사용자의 만료 행만 지운다.
    // 사용자당 남는 행은 마지막 로그인 이후 유효기간 안의 것으로 묶인다.
    // 한 묶음은 만료 시각을 물려받아 함께 만료되므로 묶음이 반쯤 지워진 채 남지 않는다. 만료 뒤 재사용 탐지는 잃지만,
    // 그 묶음에는 이미 쓸 수 있는 토큰이 없고 응답도 같은 401이라 막아 줄 것이 없다.
    private void deleteExpired(Long userId, LocalDateTime now) {
        List<Long> expiredIds = refreshTokenRepository.findExpiredIdsByUserId(userId, now);
        if (!expiredIds.isEmpty()) {
            refreshTokenRepository.deleteAllByIdIn(expiredIds);
        }
    }

    // 사용 완료 표시와 새 토큰 저장이 한 트랜잭션이다. 어느 쪽이 실패해도 둘 다 되돌아가서
    // "사용 완료인데 다음 토큰이 없는" 상태가 생기지 않고, 클라이언트는 같은 토큰으로 다시 시도할 수 있다.
    // 역할은 DB의 사용자에서 읽는다. 권한이 바뀌었다면 재발급 시점에 반영된다.
    // noRollbackFor: 재사용 탐지의 묶음 폐기는 401과 함께 커밋되어야 한다. 쓰기 뒤에 예외를 던지는 곳은 그 분기뿐이다.
    @Transactional(noRollbackFor = CustomException.class)
    public TokenResponse reissue(String rawRefreshToken) {
        // 400으로 따로 알리지 않는다. 거부 응답은 원인과 관계없이 같아야 하고, 받은 값을 검증 오류 응답으로 되돌려 주지도 않는다.
        if (!refreshTokenProvider.hasIssuedLength(rawRefreshToken)) {
            throw rejected("length_mismatch", null);
        }
        RefreshToken current = refreshTokenRepository
                .findByTokenHashForUpdate(refreshTokenProvider.hash(rawRefreshToken))
                .orElseThrow(() -> rejected("not_found", null));

        LocalDateTime now = LocalDateTime.now(clock);
        // 사용 완료를 폐기보다 먼저 본다. 탐지로 묶음이 폐기된 뒤에 같은 토큰이 또 와도 재사용 신호로 남아야 한다.
        if (current.isUsed()) {
            throw reuseDetected(current, now);
        }
        if (!current.isUsableAt(now)) {
            throw rejected(current.isRevoked() ? "revoked" : "expired", current.getId());
        }

        String nextRawToken = refreshTokenProvider.generate();
        RefreshToken next = refreshTokenRepository.save(current.rotate(refreshTokenProvider.hash(nextRawToken), now));

        User user = current.getUser();
        String accessToken = jwtProvider.createAccessToken(user.getId(), user.getRole());
        return TokenResponse.of(accessToken, jwtProvider.getAccessTokenValiditySeconds(),
                nextRawToken, Duration.between(now, next.getExpiresAt()).toSeconds());
    }

    // 로그아웃은 토큰 한 개가 아니라 이 로그인 전체를 끝낸다. 공격자가 먼저 순환해 두었다면 정상 사용자가 가진 것은
    // 사용 완료 토큰이라, 그 행만 폐기하면 공격자의 최신 토큰이 만료까지 산다. 다른 로그인의 묶음은 건드리지 않는다.
    // 사용 완료 토큰이어도 401을 내지 않는다. 로그아웃이 토큰 상태를 확인하는 창구가 되지 않게 탐지는 로그로만 남긴다.
    @Transactional
    public void revokeFamilyOf(String rawRefreshToken) {
        if (!refreshTokenProvider.hasIssuedLength(rawRefreshToken)) {
            return;
        }
        refreshTokenRepository.findByTokenHash(refreshTokenProvider.hash(rawRefreshToken)).ifPresent(token -> {
            int revoked = refreshTokenRepository.revokeFamily(token.getFamilyId(), LocalDateTime.now(clock));
            if (token.isUsed()) {
                log.warn("[RefreshToken] 사용 완료 토큰으로 로그아웃 userId={} familyId={} tokenId={} usedAt={} revoked={}",
                        token.getUser().getId(), token.getFamilyId(), token.getId(), token.getUsedAt(), revoked);
            }
        });
    }

    // 같은 토큰을 두 곳에서 쓰고 있다는 것만 알 뿐 어느 쪽이 공격자인지는 모른다. 들어온 토큰만 막으면 먼저 순환한
    // 공격자가 남을 수 있어 묶음 전체를 끊는다. 다시 들어올 수 있는 쪽은 비밀번호를 아는 정상 사용자뿐이다.
    private CustomException reuseDetected(RefreshToken token, LocalDateTime now) {
        int revoked = refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
        // 사용 후 경과 시간이 짧으면 클라이언트 재시도, 길면 탈취였을 가능성이 크다. 사후 판단 근거로 남긴다.
        log.warn("[RefreshToken] 재사용 탐지 userId={} familyId={} tokenId={} usedAt={} elapsedMs={} revoked={}",
                token.getUser().getId(), token.getFamilyId(), token.getId(), token.getUsedAt(),
                Duration.between(token.getUsedAt(), now).toMillis(), revoked);
        return new CustomException(ErrorCode.REFRESH_TOKEN_REUSE_DETECTED);
    }

    // 응답은 원인을 나누지 않지만 서버 로그에는 남긴다. 원문 토큰은 로그에 쓰지 않는다.
    private CustomException rejected(String reason, Long tokenId) {
        log.warn("[RefreshToken] 재발급 거부 reason={} tokenId={}", reason, tokenId);
        return new CustomException(ErrorCode.REFRESH_TOKEN_INVALID);
    }
}
