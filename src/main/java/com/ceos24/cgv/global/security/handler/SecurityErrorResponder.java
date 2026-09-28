package com.ceos24.cgv.global.security.handler;

import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// 필터 단계의 실패는 DispatcherServlet에 닿기 전이라 @RestControllerAdvice가 잡지 못한다.
// 컨트롤러 오류와 같은 ApiResponse 형식을 유지하려고 응답을 직접 쓴다.
@Component
@RequiredArgsConstructor
public class SecurityErrorResponder {

    // Boot 4가 등록하는 Jackson 3 매퍼. MVC 응답과 같은 설정으로 직렬화된다.
    private final JsonMapper jsonMapper;

    public void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 서블릿 기본 인코딩은 ISO-8859-1이라 지정하지 않으면 한글 메시지가 깨진다.
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(response.getWriter(), ApiResponse.error(errorCode));
    }
}
