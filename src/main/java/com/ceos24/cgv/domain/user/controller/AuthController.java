package com.ceos24.cgv.domain.user.controller;

import com.ceos24.cgv.domain.user.dto.LoginRequest;
import com.ceos24.cgv.domain.user.dto.LogoutRequest;
import com.ceos24.cgv.domain.user.dto.SignupRequest;
import com.ceos24.cgv.domain.user.dto.SignupResponse;
import com.ceos24.cgv.domain.user.dto.TokenReissueRequest;
import com.ceos24.cgv.domain.user.dto.TokenResponse;
import com.ceos24.cgv.domain.user.service.AuthService;
import com.ceos24.cgv.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "회원가입 / 로그인")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "회원가입 — 권한은 항상 USER로 생성")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest req) {
        return ApiResponse.success(authService.signup(req));
    }

    @Operation(summary = "로그인 — Access Token 발급. 계정 없음과 비밀번호 불일치는 같은 응답")
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.success(authService.login(req));
    }

    @Operation(summary = "액세스 토큰 재발급 — 리프레시 토큰도 새로 발급하고, 보낸 토큰은 사용 완료되어 다시 쓸 수 없음")
    @PostMapping("/reissue")
    public ApiResponse<TokenResponse> reissue(@Valid @RequestBody TokenReissueRequest req) {
        return ApiResponse.success(authService.reissue(req));
    }

    @Operation(summary = "로그아웃 — 본문 리프레시 토큰이 속한 로그인의 토큰 전체 폐기. 없거나 이미 폐기·사용된 토큰이어도 성공")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest req) {
        authService.logout(req);
        return ApiResponse.success();
    }
}
