package com.ceos24.cgv.domain.user.service;

import com.ceos24.cgv.domain.user.dto.LoginRequest;
import com.ceos24.cgv.domain.user.dto.LogoutRequest;
import com.ceos24.cgv.domain.user.dto.SignupRequest;
import com.ceos24.cgv.domain.user.dto.SignupResponse;
import com.ceos24.cgv.domain.user.dto.TokenReissueRequest;
import com.ceos24.cgv.domain.user.dto.TokenResponse;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.domain.user.repository.UserRepository;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.global.security.LoginUserDetails;
import com.ceos24.cgv.global.security.refresh.RefreshTokenProvider;
import com.ceos24.cgv.global.security.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtProvider jwtProvider;
    private final RefreshTokenProvider refreshTokenProvider;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public SignupResponse signup(SignupRequest req) {
        if (userRepository.existsByLoginId(req.loginId())) {
            throw new CustomException(ErrorCode.DUPLICATE_LOGIN_ID);
        }

        User user = User.builder()
                .loginId(req.loginId())
                .password(passwordEncoder.encode(req.password()))
                .name(req.name())
                .birthDate(req.birthDate())
                .email(req.email())
                .phoneNumber(req.phoneNumber())
                .build();

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // pre-check와 INSERT 사이에 같은 아이디가 먼저 가입한 경우다. 지금은 users의 unique 제약이 login_id뿐이지만,
            // 제약이 늘면 다른 위반도 이 응답으로 나간다. 그때 응답만 보고는 원인을 알 수 없으므로 DB 메시지를 남긴다.
            log.warn("[Signup] 저장 중 무결성 위반, DUPLICATE_LOGIN_ID로 응답 cause={}",
                    e.getMostSpecificCause().getMessage());
            throw new CustomException(ErrorCode.DUPLICATE_LOGIN_ID);
        }
        return SignupResponse.from(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(req.loginId(), req.password()));
        } catch (BadCredentialsException e) {
            // 계정 없음도 여기로 온다(DaoAuthenticationProvider가 변환). 다른 AuthenticationException은
            // 잡지 않는다. DB 장애 같은 InternalAuthenticationServiceException이 로그인 실패로 가려지면 안 된다.
            throw new CustomException(ErrorCode.LOGIN_FAILED);
        }

        LoginUserDetails principal = (LoginUserDetails) authentication.getPrincipal();
        String accessToken = jwtProvider.createAccessToken(principal.getUserId(), principal.getRole());
        String refreshToken = refreshTokenService.issue(principal.getUserId());
        return TokenResponse.of(accessToken, jwtProvider.getAccessTokenValiditySeconds(),
                refreshToken, refreshTokenProvider.getValiditySeconds());
    }

    // 잠금 대기 초과는 트랜잭션이 끝난 뒤에 바꾼다. 안에서 잡으면 트랜잭션이 이미 롤백 전용인지가 저장소 메서드의
    // 트랜잭션 설정과 DB 오류 분류에 달려 있고, 롤백 전용이면 커밋 시점에 UnexpectedRollbackException(500)이 된다.
    // SUPPORTS인 이유: 스스로 트랜잭션을 열지 않되, 바깥 트랜잭션이 있으면(시나리오 테스트) 합류해 미커밋 데이터를 본다.
    @Transactional(propagation = Propagation.SUPPORTS)
    public TokenResponse reissue(TokenReissueRequest req) {
        try {
            return refreshTokenService.reissue(req.refreshToken());
        } catch (ConcurrencyFailureException e) {
            // 같은 토큰을 쥔 앞 요청이 끝나지 않았다. 409로 재시도를 안내하면 그 재시도는 사용 완료 토큰이 되므로 재로그인으로 보낸다.
            log.warn("[RefreshToken] 재발급 거부 reason=lock_timeout");
            throw new CustomException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    // 없는 토큰이어도 실패로 알리지 않는다. 클라이언트는 어차피 가진 토큰을 버리므로 알려도 할 일이 없다.
    // 이 토큰으로 발급된 액세스 토큰은 만료 전까지 계속 유효하다. 막으려면 액세스 토큰 차단 목록이 필요하다.
    @Transactional
    public void logout(LogoutRequest req) {
        refreshTokenService.revokeFamilyOf(req.refreshToken());
    }
}
