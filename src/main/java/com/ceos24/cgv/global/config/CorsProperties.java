package com.ceos24.cgv.global.config;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * @param allowedOrigins 브라우저에서 API를 호출할 프런트엔드 출처. 스킴·호스트·포트까지 정확히 적는다(예: http://localhost:3000).
 */
@Validated
@ConfigurationProperties("cors")
public record CorsProperties(
        @NotEmpty List<String> allowedOrigins
) {
}
