package com.ceos24.cgv.global.security.refresh;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("refresh-token")
public record RefreshTokenProperties(
        @NotNull Duration validity
) {
}
