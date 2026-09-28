package com.ceos24.cgv.global.security;

import com.ceos24.cgv.domain.user.entity.RefreshToken;
import com.ceos24.cgv.domain.user.entity.Role;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.domain.user.repository.RefreshTokenRepository;
import com.ceos24.cgv.global.security.jwt.JwtProperties;
import com.ceos24.cgv.global.security.jwt.JwtProvider;
import com.ceos24.cgv.global.security.refresh.RefreshTokenProperties;
import com.ceos24.cgv.global.security.refresh.RefreshTokenProvider;
import com.ceos24.cgv.support.AuthScenarioTest;
import com.ceos24.cgv.support.TestFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 리프레시 토큰 시나리오. 세션 4와 같이 로그인 API로 받은 토큰을 실제 필터 체인에 태운다.
class RefreshTokenScenarioTest extends AuthScenarioTest {

    private static final String PROTECTED_API = "/api/reservations";
    private static final String REISSUE_API = "/api/auth/reissue";
    private static final String LOGOUT_API = "/api/auth/logout";

    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RefreshTokenProvider refreshTokenProvider;
    @Autowired RefreshTokenProperties refreshTokenProperties;
    @Autowired JwtProvider jwtProvider;
    @Autowired JwtProperties jwtProperties;

    @Test
    @DisplayName("로그인하면 액세스 토큰과 리프레시 토큰을 함께 발급한다")
    void 로그인하면_액세스_토큰과_리프레시_토큰을_함께_발급한다() throws Exception {
        Long userId = signup("refresh01");

        String body = loginRequest("refresh01", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.refreshTokenExpiresIn")
                        .value(refreshTokenProperties.validity().toSeconds()))
                .andReturn().getResponse().getContentAsString();

        String accessToken = JsonPath.read(body, "$.data.accessToken");
        String refreshToken = JsonPath.read(body, "$.data.refreshToken");
        assertThat(jwtProvider.parse(accessToken).userId()).isEqualTo(userId);
        assertThat(refreshToken).hasSize(43).isNotEqualTo(accessToken);
    }

    @Test
    @DisplayName("DB에는 리프레시 토큰 원문이 아니라 SHA-256 해시가 저장된다")
    void DB에는_리프레시_토큰_원문이_아니라_해시가_저장된다() throws Exception {
        Long userId = signup("refresh01");
        LocalDateTime before = LocalDateTime.now();
        String refreshToken = loginForRefreshToken("refresh01");
        LocalDateTime after = LocalDateTime.now();
        flushAndClear();

        List<RefreshToken> saved = tokensOf(userId);
        assertThat(saved).hasSize(1);
        RefreshToken token = saved.getFirst();
        assertThat(token.getTokenHash())
                .isNotEqualTo(refreshToken)
                .isEqualTo(refreshTokenProvider.hash(refreshToken));
        assertThat(refreshTokenRepository.findByTokenHash(refreshToken)).isEmpty();
        assertThat(token.getExpiresAt())
                .isBetween(before.plus(refreshTokenProperties.validity()), after.plus(refreshTokenProperties.validity()));
        assertThat(token.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("로그인할 때마다 새 리프레시 토큰이 생겨 기기별로 따로 유지된다")
    void 로그인할_때마다_새_리프레시_토큰이_생긴다() throws Exception {
        Long userId = signup("refresh01");

        String first = loginForRefreshToken("refresh01");
        String second = loginForRefreshToken("refresh01");
        flushAndClear();

        assertThat(first).isNotEqualTo(second);
        assertThat(tokensOf(userId)).hasSize(2).noneMatch(RefreshToken::isRevoked);
    }

    @Test
    @DisplayName("로그인에 실패하면 리프레시 토큰을 저장하지 않는다")
    void 로그인에_실패하면_리프레시_토큰을_저장하지_않는다() throws Exception {
        Long userId = signup("refresh01");

        loginRequest("refresh01", "wrongpass1!")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data").doesNotExist());
        flushAndClear();

        assertThat(tokensOf(userId)).isEmpty();
    }

    // 리프레시 토큰에는 점이 없어 JWT 세 조각으로 나뉘지 않는다. 필터가 형식 오류로 기록하고 보호 경로가 거부한다.
    @Test
    @DisplayName("리프레시 토큰을 Authorization 헤더에 넣어 보호 API를 호출하면 401 TOKEN_INVALID")
    void 리프레시_토큰을_Authorization_헤더에_넣으면_401_TOKEN_INVALID() throws Exception {
        signup("refresh01");
        String refreshToken = loginForRefreshToken("refresh01");

        mockMvc.perform(get(PROTECTED_API).with(bearer(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    // ─── 재발급 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 리프레시 토큰으로 재발급하면 새 액세스 토큰으로 보호 API를 정상 호출할 수 있다")
    void 유효한_리프레시_토큰으로_재발급한_토큰으로_보호_API를_호출할_수_있다() throws Exception {
        Long userId = signup("refresh01");
        String refreshToken = loginForRefreshToken("refresh01");

        String body = reissueRequest(refreshToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(jwtProperties.accessTokenValidity().toSeconds()))
                .andReturn().getResponse().getContentAsString();
        String reissued = JsonPath.read(body, "$.data.accessToken");

        assertThat(jwtProvider.parse(reissued)).isEqualTo(new AuthUser(userId, Role.USER));
        mockMvc.perform(get(PROTECTED_API).with(bearer(reissued)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("재발급하면 새 리프레시 토큰을 함께 주고, 보낸 토큰은 사용 완료되며 새 토큰은 같은 묶음·만료 시각을 이어받는다")
    void 재발급하면_새_리프레시_토큰을_주고_이전_토큰은_사용_완료된다() throws Exception {
        Long userId = signup("refresh01");
        String oldToken = loginForRefreshToken("refresh01");
        flushAndClear();
        LocalDateTime loginExpiresAt = tokenOf(oldToken).getExpiresAt();

        String body = reissueRequest(oldToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andReturn().getResponse().getContentAsString();
        String newToken = JsonPath.read(body, "$.data.refreshToken");
        long refreshTokenExpiresIn = ((Number) JsonPath.read(body, "$.data.refreshTokenExpiresIn")).longValue();
        flushAndClear();

        assertThat(newToken).hasSize(43).isNotEqualTo(oldToken);
        // 만료는 로그인 시점 기준으로 고정이라 남은 시간은 전체 수명을 넘지 않는다
        assertThat(refreshTokenExpiresIn).isPositive()
                .isLessThanOrEqualTo(refreshTokenProperties.validity().toSeconds());

        RefreshToken used = tokenOf(oldToken);
        assertThat(used.isUsed()).isTrue();
        assertThat(used.isRevoked()).isFalse();

        RefreshToken next = tokenOf(newToken);
        assertThat(next.isUsed()).isFalse();
        assertThat(next.getFamilyId()).isEqualTo(used.getFamilyId());
        assertThat(next.getExpiresAt()).isEqualTo(loginExpiresAt);
        assertThat(tokensOf(userId)).hasSize(2);
    }

    @Test
    @DisplayName("재발급으로 받은 새 리프레시 토큰으로 다시 재발급할 수 있다")
    void 새_리프레시_토큰으로_다시_재발급할_수_있다() throws Exception {
        signup("refresh01");
        String first = loginForRefreshToken("refresh01");
        String second = reissuedRefreshToken(first);

        String body = reissueRequest(second)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String third = JsonPath.read(body, "$.data.refreshToken");

        assertThat(third).isNotEqualTo(first).isNotEqualTo(second);
        mockMvc.perform(get(PROTECTED_API).with(bearer((String) JsonPath.read(body, "$.data.accessToken"))))
                .andExpect(status().isOk());
    }

    // ─── 재사용 탐지 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("사용 완료된 리프레시 토큰으로 다시 재발급하면 401 REFRESH_TOKEN_REUSE_DETECTED이고 묶음 전체가 폐기된다")
    void 사용_완료된_리프레시_토큰으로_재발급하면_재사용_탐지() throws Exception {
        signup("refresh01");
        String oldToken = loginForRefreshToken("refresh01");
        String latest = reissuedRefreshToken(oldToken);
        flushAndClear();

        reissueRequest(oldToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        flushAndClear();

        assertThat(familyOf(oldToken)).hasSize(2).allMatch(RefreshToken::isRevoked);
        assertThat(tokenOf(latest).isUsed()).isFalse();
    }

    @Test
    @DisplayName("재사용이 탐지되면 같은 묶음의 최신 리프레시 토큰으로도 재발급할 수 없다")
    void 재사용_탐지_후_같은_묶음의_최신_토큰은_401() throws Exception {
        signup("refresh01");
        String first = loginForRefreshToken("refresh01");
        String second = reissuedRefreshToken(first);
        String latest = reissuedRefreshToken(second);
        flushAndClear();

        reissueRequest(first).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"));
        flushAndClear();

        // 최신 토큰은 사용된 적 없이 폐기된 것이라 재사용이 아니라 일반 거부다
        reissueRequest(latest)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        assertThat(familyOf(first)).hasSize(3).allMatch(RefreshToken::isRevoked);
    }

    @Test
    @DisplayName("재사용이 탐지돼도 다른 로그인에서 받은 리프레시 토큰은 영향 없이 재발급된다")
    void 재사용_탐지는_다른_로그인의_묶음에_영향이_없다() throws Exception {
        signup("refresh01");
        String phone = loginForRefreshToken("refresh01");
        String laptop = loginForRefreshToken("refresh01");
        reissuedRefreshToken(phone);
        flushAndClear();

        reissueRequest(phone).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"));
        flushAndClear();

        assertThat(familyOf(laptop)).hasSize(1).noneMatch(RefreshToken::isRevoked);
        reissueRequest(laptop).andExpect(status().isOk());
    }

    // 동시 재발급에서 두 번째 요청이 묶음을 폐기하면 원래 토큰도 폐기 상태가 된다.
    // 세 번째부터가 폐기 확인에 먼저 걸려 일반 거부로 바뀌면 안 된다.
    @Test
    @DisplayName("묶음이 이미 폐기된 뒤에도 사용 완료 토큰이 다시 오면 재사용으로 탐지하고 처음 폐기 시각은 유지된다")
    void 묶음이_폐기된_뒤에도_사용_완료_토큰은_재사용으로_탐지한다() throws Exception {
        signup("refresh01");
        String oldToken = loginForRefreshToken("refresh01");
        reissuedRefreshToken(oldToken);
        flushAndClear();

        reissueRequest(oldToken).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"));
        flushAndClear();
        LocalDateTime firstRevokedAt = tokenOf(oldToken).getRevokedAt();

        reissueRequest(oldToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"));
        flushAndClear();
        assertThat(tokenOf(oldToken).getRevokedAt()).isEqualTo(firstRevokedAt);
    }

    // 실제 재발급 상황은 액세스 토큰이 만료된 뒤다. 클라이언트가 만료된 토큰을 헤더에 남겨 둔 채 불러도 막히면 안 된다.
    @Test
    @DisplayName("만료된 액세스 토큰을 헤더에 단 채로도 재발급할 수 있다")
    void 만료된_액세스_토큰을_헤더에_단_채로도_재발급할_수_있다() throws Exception {
        Long userId = signup("refresh01");
        String refreshToken = loginForRefreshToken("refresh01");

        reissueRequest(refreshToken, expiredAccessToken(userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists());
    }

    @Test
    @DisplayName("재발급한 액세스 토큰의 권한은 리프레시 토큰이 아니라 DB의 사용자에서 정해진다")
    void 재발급한_액세스_토큰의_권한은_DB의_사용자에서_정해진다() throws Exception {
        User admin = persistAdmin("admin01");
        String refreshToken = storeRefreshToken(admin, LocalDateTime.now().plusDays(1));

        String body = reissueRequest(refreshToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String reissued = JsonPath.read(body, "$.data.accessToken");

        assertThat(jwtProvider.parse(reissued).role()).isEqualTo(Role.ADMIN);
        mockMvc.perform(get("/api/admin/check").with(bearer(reissued)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("만료된 리프레시 토큰으로 재발급하면 401 REFRESH_TOKEN_INVALID")
    void 만료된_리프레시_토큰이면_401_REFRESH_TOKEN_INVALID() throws Exception {
        User user = persist(TestFixtures.user("expired01"));
        // 만료 시각을 과거로 둔 행을 직접 저장한다. 시간이 지나기를 기다리지 않는다.
        String refreshToken = storeRefreshToken(user, LocalDateTime.now().minusMinutes(1));

        reissueRequest(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("존재하지 않거나 임의로 만든 리프레시 토큰으로 재발급하면 401 REFRESH_TOKEN_INVALID")
    void 존재하지_않거나_임의로_만든_리프레시_토큰이면_401_REFRESH_TOKEN_INVALID() throws Exception {
        // 형식은 진짜와 같지만 발급된 적 없는 토큰
        reissueRequest(refreshTokenProvider.generate())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        // 형식부터 다른 임의 문자열
        reissueRequest("made-up-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("액세스 토큰을 재발급 API 본문에 넣으면 401 REFRESH_TOKEN_INVALID")
    void 액세스_토큰을_재발급_본문에_넣으면_401_REFRESH_TOKEN_INVALID() throws Exception {
        signup("refresh01");
        String accessToken = login("refresh01");

        reissueRequest(accessToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    @Test
    @DisplayName("만료·미발급·액세스 토큰으로 거부된 응답은 본문까지 모두 같다")
    void 재발급_거부_응답은_원인과_관계없이_같다() throws Exception {
        User user = persist(TestFixtures.user("expired01"));
        String expired = storeRefreshToken(user, LocalDateTime.now().minusMinutes(1));
        signup("refresh01");
        String accessToken = login("refresh01");

        String expiredBody = rejectedBody(expired);
        assertThat(rejectedBody(refreshTokenProvider.generate())).isEqualTo(expiredBody);
        assertThat(rejectedBody(accessToken)).isEqualTo(expiredBody);
    }

    @Test
    @DisplayName("리프레시 토큰이 비어 있거나 빠지면 400 INVALID_INPUT_VALUE")
    void 리프레시_토큰이_비어_있으면_400() throws Exception {
        reissueRequest("")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
        mockMvc.perform(post(REISSUE_API).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    // ─── 로그아웃 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("로그아웃하면 DB의 해당 리프레시 토큰이 폐기 상태가 된다")
    void 로그아웃하면_DB의_리프레시_토큰이_폐기_상태가_된다() throws Exception {
        signup("refresh01");
        String refreshToken = loginForRefreshToken("refresh01");

        logoutRequest(refreshToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        // 1차 캐시가 아니라 DB에 반영됐는지 본다
        flushAndClear();

        RefreshToken saved = refreshTokenRepository.findByTokenHash(refreshTokenProvider.hash(refreshToken))
                .orElseThrow();
        assertThat(saved.isRevoked()).isTrue();
        assertThat(saved.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("로그아웃한 리프레시 토큰으로 재발급하면 401 REFRESH_TOKEN_INVALID이고 다른 거부 응답과 본문이 같다")
    void 로그아웃한_리프레시_토큰으로_재발급하면_401_REFRESH_TOKEN_INVALID() throws Exception {
        signup("refresh01");
        String refreshToken = loginForRefreshToken("refresh01");
        logoutRequest(refreshToken).andExpect(status().isOk());
        flushAndClear();

        String revokedBody = reissueRequest(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"))
                .andReturn().getResponse().getContentAsString();
        // 폐기됐다는 사실이 응답에 드러나지 않는다
        assertThat(revokedBody).isEqualTo(rejectedBody(refreshTokenProvider.generate()));
    }

    @Test
    @DisplayName("한 기기에서 로그아웃해도 같은 사용자의 다른 기기 리프레시 토큰은 계속 재발급된다")
    void 로그아웃해도_다른_기기의_리프레시_토큰은_유지된다() throws Exception {
        signup("refresh01");
        String phone = loginForRefreshToken("refresh01");
        String laptop = loginForRefreshToken("refresh01");

        logoutRequest(phone).andExpect(status().isOk());
        flushAndClear();

        reissueRequest(phone).andExpect(status().isUnauthorized());
        reissueRequest(laptop).andExpect(status().isOk());
    }

    @Test
    @DisplayName("없는 토큰이나 이미 로그아웃한 토큰으로 로그아웃해도 200이고 처음 폐기 시각은 바뀌지 않는다")
    void 로그아웃은_멱등이다() throws Exception {
        logoutRequest(refreshTokenProvider.generate()).andExpect(status().isOk());

        signup("refresh01");
        String refreshToken = loginForRefreshToken("refresh01");
        logoutRequest(refreshToken).andExpect(status().isOk());
        flushAndClear();
        LocalDateTime firstRevokedAt = revokedAtOf(refreshToken);

        logoutRequest(refreshToken).andExpect(status().isOk());
        flushAndClear();
        assertThat(revokedAtOf(refreshToken)).isEqualTo(firstRevokedAt);
    }

    @Test
    @DisplayName("최신 리프레시 토큰으로 로그아웃하면 같은 로그인에서 이어진 토큰이 모두 폐기된다")
    void 로그아웃하면_같은_로그인의_토큰_묶음이_모두_폐기된다() throws Exception {
        signup("refresh01");
        String first = loginForRefreshToken("refresh01");
        String latest = reissuedRefreshToken(reissuedRefreshToken(first));

        logoutRequest(latest).andExpect(status().isOk());
        flushAndClear();

        assertThat(familyOf(first)).hasSize(3).allMatch(RefreshToken::isRevoked);
    }

    // 공격자가 훔친 토큰으로 먼저 재발급하면 정상 사용자에게 남는 것은 사용 완료 토큰이다.
    // 그 행만 폐기하면 공격자가 받은 최신 토큰 A가 만료까지 산다.
    @Test
    @DisplayName("사용 완료된 이전 토큰으로 로그아웃해도 200이고, 그 뒤 최신 토큰으로 재발급하면 401 REFRESH_TOKEN_INVALID")
    void 사용_완료된_이전_토큰으로_로그아웃해도_최신_토큰까지_폐기된다() throws Exception {
        signup("refresh01");
        String oldToken = loginForRefreshToken("refresh01");
        String tokenA = reissuedRefreshToken(oldToken);
        flushAndClear();

        logoutRequest(oldToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        flushAndClear();

        reissueRequest(tokenA)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
        assertThat(familyOf(oldToken)).hasSize(2).allMatch(RefreshToken::isRevoked);
    }

    @Test
    @DisplayName("로그아웃은 다른 로그인의 토큰 묶음을 폐기하지 않는다")
    void 로그아웃은_다른_로그인의_묶음을_폐기하지_않는다() throws Exception {
        signup("refresh01");
        String phone = loginForRefreshToken("refresh01");
        String laptop = reissuedRefreshToken(loginForRefreshToken("refresh01"));

        logoutRequest(reissuedRefreshToken(phone)).andExpect(status().isOk());
        flushAndClear();

        assertThat(familyOf(laptop)).noneMatch(RefreshToken::isRevoked);
        reissueRequest(laptop).andExpect(status().isOk());
    }

    @Test
    @DisplayName("리프레시 토큰이 비어 있으면 로그아웃은 400 INVALID_INPUT_VALUE")
    void 로그아웃_리프레시_토큰이_비어_있으면_400() throws Exception {
        logoutRequest("")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    // 한계를 드러내는 테스트. 액세스 토큰은 DB를 보지 않고 서명만으로 검증하므로 로그아웃과 무관하게 만료까지 산다.
    @Test
    @DisplayName("로그아웃 후에도 이미 발급된 액세스 토큰은 만료 전까지 보호 API를 호출할 수 있다")
    void 로그아웃_후에도_액세스_토큰은_만료_전까지_유효하다() throws Exception {
        signup("refresh01");
        String body = loginBody("refresh01");
        String accessToken = JsonPath.read(body, "$.data.accessToken");
        String refreshToken = JsonPath.read(body, "$.data.refreshToken");

        logoutRequest(refreshToken).andExpect(status().isOk());
        flushAndClear();

        mockMvc.perform(get(PROTECTED_API).with(bearer(accessToken)))
                .andExpect(status().isOk());
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────────

    private ResultActions logoutRequest(String refreshToken) throws Exception {
        return mockMvc.perform(post(LOGOUT_API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private LocalDateTime revokedAtOf(String refreshToken) {
        return refreshTokenRepository.findByTokenHash(refreshTokenProvider.hash(refreshToken))
                .orElseThrow().getRevokedAt();
    }

    private ResultActions reissueRequest(String refreshToken) throws Exception {
        return mockMvc.perform(post(REISSUE_API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private ResultActions reissueRequest(String refreshToken, String accessTokenInHeader) throws Exception {
        return mockMvc.perform(post(REISSUE_API).with(bearer(accessTokenInHeader))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private String reissuedRefreshToken(String refreshToken) throws Exception {
        String body = reissueRequest(refreshToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.refreshToken");
    }

    private RefreshToken tokenOf(String rawToken) {
        return refreshTokenRepository.findByTokenHash(refreshTokenProvider.hash(rawToken)).orElseThrow();
    }

    private List<RefreshToken> familyOf(String rawToken) {
        String familyId = tokenOf(rawToken).getFamilyId();
        return refreshTokenRepository.findAll().stream()
                .filter(token -> token.getFamilyId().equals(familyId))
                .toList();
    }

    private String rejectedBody(String refreshToken) throws Exception {
        return reissueRequest(refreshToken)
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
    }

    // 로그인 API를 거치지 않고 원하는 만료 시각의 토큰을 만든다. 저장은 운영 코드와 같이 해시로 한다.
    private String storeRefreshToken(User user, LocalDateTime expiresAt) {
        String rawToken = refreshTokenProvider.generate();
        persist(RefreshToken.builder()
                .user(user)
                .tokenHash(refreshTokenProvider.hash(rawToken))
                .familyId(UUID.randomUUID().toString())
                .expiresAt(expiresAt)
                .build());
        flushAndClear();
        return rawToken;
    }

    private String expiredAccessToken(Long userId) {
        Instant issuedAt = Instant.now().minus(jwtProperties.accessTokenValidity()).minus(Duration.ofMinutes(1));
        return new JwtProvider(jwtProperties, Clock.fixed(issuedAt, ZoneOffset.UTC))
                .createAccessToken(userId, Role.USER);
    }

    private List<RefreshToken> tokensOf(Long userId) {
        return refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(userId))
                .toList();
    }
}
