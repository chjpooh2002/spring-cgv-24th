package com.ceos24.cgv.global.config;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.user.entity.Role;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.global.security.jwt.JwtAuthenticationFilter;
import com.ceos24.cgv.global.security.jwt.JwtProperties;
import com.ceos24.cgv.global.security.jwt.JwtProvider;
import com.ceos24.cgv.support.TestFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 경로 규칙, 인증·인가 실패 응답, 필터 등록 방식처럼 특정 컨트롤러에 속하지 않는 보안 설정을 확인한다.
@SpringBootTest
@Transactional
class SecurityConfigTest {

    @Autowired WebApplicationContext wac;
    @Autowired EntityManager em;
    @Autowired JwtProvider jwtProvider;
    @Autowired JwtProperties jwtProperties;
    @Autowired FilterChainProxy filterChainProxy;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
    }

    @Test
    void 공개_조회_API는_토큰_없이_호출할_수_있다() throws Exception {
        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk());
    }

    @Test
    void 공개_조회_API는_만료된_토큰을_보내도_익명으로_통과한다() throws Exception {
        mockMvc.perform(get("/api/movies").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken())))
                .andExpect(status().isOk());
    }

    @Test
    void 유효한_토큰이면_CSRF_토큰_없이_보호된_쓰기_API를_호출할_수_있다() throws Exception {
        User user = TestFixtures.user("securitytest");
        Branch branch = TestFixtures.branch("강남점");
        em.persist(user);
        em.persist(branch);

        mockMvc.perform(post("/api/branches/{id}/likes", branch.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtProvider.createAccessToken(user.getId(), Role.USER))))
                .andExpect(status().isOk());
    }

    @Test
    void 보호_API에_토큰이_없으면_401_TOKEN_NOT_EXIST() throws Exception {
        mockMvc.perform(get("/api/purchases"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TOKEN_NOT_EXIST"))
                .andExpect(jsonPath("$.message").value("인증 토큰이 없습니다."));
    }

    @Test
    void 보호_API에_만료된_토큰이면_401_TOKEN_EXPIRED() throws Exception {
        mockMvc.perform(get("/api/purchases").header(HttpHeaders.AUTHORIZATION, bearer(expiredToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void 보호_API에_변조된_토큰이면_401_TOKEN_INVALID() throws Exception {
        mockMvc.perform(get("/api/purchases").header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    void 찜_목록은_공개_상세_경로와_겹쳐도_인증이_필요하다() throws Exception {
        mockMvc.perform(get("/api/branches/likes"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/movies/likes"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 일반_사용자가_관리자_경로를_호출하면_403_ACCESS_DENIED() throws Exception {
        mockMvc.perform(get("/api/admin/anything")
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtProvider.createAccessToken(1L, Role.USER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void 익명_사용자가_관리자_경로를_호출하면_403이_아니라_401() throws Exception {
        mockMvc.perform(get("/api/admin/anything"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_NOT_EXIST"));
    }

    @Test
    void 세션을_만들지_않는다() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/movies"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // 빈이면 Boot가 서블릿 필터로도 등록해 요청마다 두 번 실행된다.
    @Test
    void JWT_필터는_빈이_아니고_Security_체인에만_한_번_등록된다() {
        assertThat(wac.getBeansOfType(JwtAuthenticationFilter.class)).isEmpty();
        assertThat(filterChainProxy.getFilterChains().getFirst().getFilters())
                .filteredOn(JwtAuthenticationFilter.class::isInstance)
                .hasSize(1);
    }

    // ─── CORS ─────────────────────────────────────────────────────────────────
    // 브라우저는 Authorization 헤더를 실은 다른 출처 요청 전에 OPTIONS preflight를 토큰 없이 보낸다.
    // 이 요청이 인증 규칙에 걸리면 본 요청은 보내지지도 않는다.

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    @Test
    void 허용된_출처의_preflight는_보호_경로여도_토큰_없이_통과한다() throws Exception {
        mockMvc.perform(options("/api/purchases")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")))
                // 쿠키를 쓰지 않으므로 자격 증명 전송을 허용하지 않는다
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    // 공개 규칙은 GET에만 걸려 있어 OPTIONS는 공개 경로라도 기본값(인증 필요)에 걸렸다.
    @Test
    void 허용된_출처의_preflight는_GET만_공개된_경로에서도_통과한다() throws Exception {
        mockMvc.perform(options("/api/movies")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void 허용하지_않은_출처의_preflight는_403() throws Exception {
        mockMvc.perform(options("/api/purchases")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // preflight만 열렸을 뿐 본 요청의 인증 규칙은 그대로다. 401에도 CORS 헤더가 있어야 브라우저가 오류 본문을 읽는다.
    @Test
    void 허용된_출처의_본_요청도_토큰이_없으면_401이고_CORS_헤더가_붙는다() throws Exception {
        mockMvc.perform(get("/api/purchases").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_NOT_EXIST"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    private String expiredToken() {
        Instant issuedAt = Instant.now().minus(jwtProperties.accessTokenValidity()).minus(Duration.ofMinutes(1));
        return new JwtProvider(jwtProperties, Clock.fixed(issuedAt, ZoneOffset.UTC))
                .createAccessToken(1L, Role.USER);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
