package com.ceos24.cgv.global.security;

import com.ceos24.cgv.domain.user.entity.RefreshToken;
import com.ceos24.cgv.domain.user.repository.RefreshTokenRepository;
import com.ceos24.cgv.global.security.refresh.RefreshTokenProvider;
import com.ceos24.cgv.support.AuthScenarioTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청마다 트랜잭션이 따로 열리고 커밋되어야 동시성과 커밋 여부를 볼 수 있다. 테스트 트랜잭션 안에서는
// 모든 요청이 한 트랜잭션에 합류해 잠금 경쟁이 생기지 않고, 롤백될 변경도 같은 트랜잭션 안에서는 보인다.
// 그래서 상위 클래스의 @Transactional을 끄고 실제 필터 체인으로 요청을 보낸다.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefreshTokenConcurrencyTest extends AuthScenarioTest {

    private static final String REISSUE_API = "/api/auth/reissue";
    private static final String LOGIN_ID_PREFIX = "rtconc";

    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired RefreshTokenProvider refreshTokenProvider;
    @Autowired TransactionTemplate transactionTemplate;

    // 커밋하는 테스트라 롤백에 기댈 수 없다. 이 클래스가 만든 사용자와 그 토큰만 지운다.
    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            em.createQuery("""
                    delete from RefreshToken rt
                    where rt.user.id in (select u.id from User u where u.loginId like :prefix)
                    """).setParameter("prefix", LOGIN_ID_PREFIX + "%").executeUpdate();
            em.createQuery("delete from User u where u.loginId like :prefix")
                    .setParameter("prefix", LOGIN_ID_PREFIX + "%").executeUpdate();
        });
    }

    // 동시에 들어온 같은 토큰은 재시도인지 탈취인지 구분할 수 없어 재사용으로 본다(유예 시간 없음).
    // 승자가 받은 새 토큰까지 폐기되므로, 동시에 두 번 보낸 정상 클라이언트도 다시 로그인하게 된다.
    @Test
    @DisplayName("같은 리프레시 토큰으로 동시에 재발급하면 1건만 성공하고, 나머지는 재사용 탐지이며 승자의 새 토큰도 폐기된다")
    void 같은_리프레시_토큰으로_동시_재발급하면_1건만_성공한다() throws Exception {
        signup(LOGIN_ID_PREFIX + "01");
        String token = loginForRefreshToken(LOGIN_ID_PREFIX + "01");
        int requests = 10;

        List<MvcResult> results = reissueConcurrently(token, requests);

        List<MvcResult> succeeded = results.stream()
                .filter(r -> r.getResponse().getStatus() == 200).toList();
        assertThat(succeeded).hasSize(1);
        assertThat(results).filteredOn(r -> r.getResponse().getStatus() != 200).hasSize(requests - 1)
                .allSatisfy(r -> {
                    assertThat(r.getResponse().getStatus()).isEqualTo(401);
                    assertThat(codeOf(r)).isEqualTo("REFRESH_TOKEN_REUSE_DETECTED");
                });

        // 커밋된 결과를 새 트랜잭션에서 다시 읽는다. 묶음이 갈라지지 않았고(2행) 승자의 새 토큰까지 모두 폐기됐다.
        String winnerToken = JsonPath.read(succeeded.getFirst().getResponse().getContentAsString(), "$.data.refreshToken");
        RefreshToken original = tokenOf(token);
        assertThat(original.isUsed()).isTrue();
        assertThat(familyOf(original)).hasSize(2).allMatch(RefreshToken::isRevoked);
        assertThat(tokenOf(winnerToken).isRevoked()).isTrue();
    }

    // 폐기와 401이 한 트랜잭션이라, 예외로 롤백되면 폐기도 사라진다. 테스트 트랜잭션 안에서는 롤백될 변경도
    // 같은 트랜잭션에서 보여 이 버그가 드러나지 않으므로 여기서 커밋된 상태를 다시 읽는다.
    @Test
    @DisplayName("재사용 탐지의 묶음 폐기는 401 응답과 함께 커밋된다")
    void 재사용_탐지의_묶음_폐기는_커밋된다() throws Exception {
        signup(LOGIN_ID_PREFIX + "01");
        String first = loginForRefreshToken(LOGIN_ID_PREFIX + "01");
        String latest = reissuedRefreshToken(first);

        reissueRequest(first)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_REUSE_DETECTED"));

        assertThat(familyOf(tokenOf(first))).hasSize(2).allMatch(RefreshToken::isRevoked);
        reissueRequest(latest)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
    }

    // 세션 5에서 확인한 함정: 쓰기 메서드가 읽기 전용 트랜잭션에서 돌면 테스트 트랜잭션 안에서는 통과하지만 폐기가 반영되지 않는다.
    @Test
    @DisplayName("사용 완료된 이전 토큰으로 로그아웃한 묶음 폐기는 커밋된다")
    void 로그아웃의_묶음_폐기는_커밋된다() throws Exception {
        signup(LOGIN_ID_PREFIX + "01");
        String oldToken = loginForRefreshToken(LOGIN_ID_PREFIX + "01");
        String tokenA = reissuedRefreshToken(oldToken);

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(oldToken)))
                .andExpect(status().isOk());

        assertThat(familyOf(tokenOf(oldToken))).hasSize(2).allMatch(RefreshToken::isRevoked);
        reissueRequest(tokenA).andExpect(status().isUnauthorized());
    }

    // 앞 요청이 잠금을 쥔 채 끝나지 않는 상황을 만든다. 잡아 두는 쪽이 트랜잭션을 열어 같은 행을 잠그고,
    // 재발급 요청이 DB의 잠금 대기 한도에 걸려 실패할 때까지 놓지 않는다.
    @Test
    @DisplayName("같은 토큰의 잠금을 기다리다 대기 시간이 초과되면 500이 아니라 401이고 토큰 상태는 바뀌지 않는다")
    void 잠금_대기_시간이_초과되면_401() throws Exception {
        signup(LOGIN_ID_PREFIX + "01");
        String token = loginForRefreshToken(LOGIN_ID_PREFIX + "01");
        String hash = refreshTokenProvider.hash(token);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                refreshTokenRepository.findByTokenHashForUpdate(hash).orElseThrow();
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            try {
                reissueRequest(token)
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));
            } finally {
                release.countDown();
                holder.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        // 판정하지 못한 요청은 아무것도 바꾸지 않는다. 잠금이 풀리면 같은 토큰으로 정상 재발급된다.
        assertThat(tokenOf(token).isUsed()).isFalse();
        reissueRequest(token).andExpect(status().isOk());
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────────

    // 모든 스레드가 준비된 뒤 한 번에 출발시켜 실제로 겹치게 만든다.
    private List<MvcResult> reissueConcurrently(String token, int requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests);
        CountDownLatch ready = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return reissueRequest(token).andReturn();
                }));
            }
            ready.await();
            start.countDown();

            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private ResultActions reissueRequest(String refreshToken) throws Exception {
        return mockMvc.perform(post(REISSUE_API)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private String reissuedRefreshToken(String refreshToken) throws Exception {
        String body = reissueRequest(refreshToken)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.refreshToken");
    }

    private String codeOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.code");
    }

    private RefreshToken tokenOf(String rawToken) {
        return refreshTokenRepository.findByTokenHash(refreshTokenProvider.hash(rawToken)).orElseThrow();
    }

    private List<RefreshToken> familyOf(RefreshToken token) {
        return refreshTokenRepository.findAll().stream()
                .filter(t -> t.getFamilyId().equals(token.getFamilyId()))
                .toList();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
