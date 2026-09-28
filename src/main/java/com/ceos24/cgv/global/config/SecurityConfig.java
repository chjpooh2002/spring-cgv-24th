package com.ceos24.cgv.global.config;

import com.ceos24.cgv.domain.user.entity.Role;
import com.ceos24.cgv.global.security.handler.JwtAccessDeniedHandler;
import com.ceos24.cgv.global.security.handler.JwtAuthenticationEntryPoint;
import com.ceos24.cgv.global.security.refresh.RefreshTokenProperties;
import com.ceos24.cgv.global.security.jwt.JwtAuthenticationFilter;
import com.ceos24.cgv.global.security.jwt.JwtProperties;
import com.ceos24.cgv.global.security.jwt.JwtProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, RefreshTokenProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationEntryPoint authenticationEntryPoint,
                                                   JwtAccessDeniedHandler accessDeniedHandler,
                                                   JwtProvider jwtProvider) throws Exception {
        http
                // 인증 수단이 Authorization 헤더뿐이라 브라우저가 자동으로 실어 보내는 자격 증명이 없다.
                // CSRF는 그 자동 전송을 악용하는 공격이므로 막을 대상이 없다.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // AnonymousAuthenticationFilter보다 앞에 둬야 토큰 인증이 익명 인증보다 먼저 자리를 잡는다.
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class)
                // 위에서부터 처음 맞는 규칙 하나만 적용된다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/admin/**").hasAuthority(Role.ADMIN.getAuthority())
                        // 아래 공개 규칙의 /{id}가 "likes"도 받아들이므로 먼저 막는다.
                        .requestMatchers(HttpMethod.GET, "/api/branches/likes", "/api/movies/likes").authenticated()
                        // 재발급·로그아웃은 액세스 토큰이 만료된 뒤에도 불러야 하므로 공개한다. 자격 증명은 본문의 리프레시 토큰이다.
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/reissue",
                                "/api/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/movies", "/api/movies/{id}",
                                "/api/branches", "/api/branches/regions", "/api/branches/{id}",
                                "/api/branches/{branchId}/products",
                                "/api/screenings", "/api/screenings/{id}/seats").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 컨테이너가 오류를 /error로 포워드할 때도 인가를 다시 거친다. 막으면 원래 오류가 401로 덮인다.
                        .requestMatchers("/error").permitAll()
                        // 새 API가 규칙 없이 추가되면 열리는 대신 잠기도록 기본값을 인증 필요로 둔다.
                        .anyRequest().authenticated());
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // AuthenticationConfiguration에서 꺼내도 같은 조립이 되지만, 어떤 UserDetailsService와
    // PasswordEncoder로 비교하는지가 코드에 드러나지 않아 직접 조립한다.
    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
