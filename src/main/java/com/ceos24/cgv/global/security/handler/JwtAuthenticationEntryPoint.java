package com.ceos24.cgv.global.security.handler;

import com.ceos24.cgv.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

// 익명 요청이 보호 경로에서 거부되면 ExceptionTranslationFilter가 호출한다.
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    // 인증 필터가 토큰 검증 실패 원인을 여기에 남긴다. 필터는 요청을 막지 않고 넘기므로,
    // 공개 경로라면 이 값은 읽히지 않고 버려진다.
    public static final String ERROR_CODE_ATTRIBUTE = JwtAuthenticationEntryPoint.class.getName() + ".ERROR_CODE";

    private final SecurityErrorResponder responder;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // 원인이 없으면 필터가 토큰을 찾지 못한 요청이다.
        ErrorCode errorCode = request.getAttribute(ERROR_CODE_ATTRIBUTE) instanceof ErrorCode code
                ? code
                : ErrorCode.TOKEN_NOT_EXIST;

        // RFC 6750: 401에는 어떤 인증 방식을 요구하는지 알리는 헤더를 붙인다.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        responder.write(response, errorCode);
    }
}
