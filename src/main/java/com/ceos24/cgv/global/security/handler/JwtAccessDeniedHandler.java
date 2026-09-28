package com.ceos24.cgv.global.security.handler;

import com.ceos24.cgv.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// 인증은 됐지만 권한이 모자랄 때만 불린다. 익명 사용자의 거부는 EntryPoint로 간다.
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponder responder;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        responder.write(response, ErrorCode.ACCESS_DENIED);
    }
}
