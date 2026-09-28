package com.ceos24.cgv.global.security.jwt;

import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.security.AuthUser;
import com.ceos24.cgv.global.security.handler.JwtAuthenticationEntryPoint;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 빈으로 등록하지 않는다. @Component로 두면 Boot가 서블릿 필터로도 자동 등록해
// Security 체인 밖에서 한 번 더 실행된다. SecurityConfig가 직접 생성해 체인에만 넣는다.
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            authenticate(token, request);
        }
        filterChain.doFilter(request, response);
    }

    // 실패해도 여기서 응답하지 않는다. 공개 경로는 토큰 상태와 무관하게 열려 있어야 하므로
    // 원인만 남기고, 보호 경로에서 거부될 때 EntryPoint가 그 원인으로 응답한다.
    private void authenticate(String token, HttpServletRequest request) {
        try {
            AuthUser authUser = jwtProvider.parse(token);
            Authentication authentication =
                    UsernamePasswordAuthenticationToken.authenticated(authUser, null, authUser.getAuthorities());

            // 기존 컨텍스트 인스턴스를 고치면 그것을 공유하는 다른 스레드에 인증이 새어 나갈 수 있어 새로 만든다.
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (CustomException e) {
            request.setAttribute(JwtAuthenticationEntryPoint.ERROR_CODE_ATTRIBUTE, e.getErrorCode());
        }
    }

    // Bearer가 아닌 스킴은 토큰이 없는 것으로 본다. 인증 스킴 이름은 대소문자를 구분하지 않는다(RFC 7235).
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
