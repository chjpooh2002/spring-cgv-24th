package com.ceos24.cgv.domain.branch.service;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.support.MySqlContainerConfig;
import com.ceos24.cgv.support.TestFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 사용자의 같은 극장 찜이 겹칠 때 행이 하나만 남는지, 진 쪽이 500이 아니라
// 매핑된 예외로 나오는지 실제 스레드로 확인한다.
// 스레드마다 트랜잭션이 따로 열려야 해서 ControllerIntegrationTest(@Transactional)를 쓰지 않는다.
@SpringBootTest
@Import(MySqlContainerConfig.class)
class BranchLikeConcurrencyTest {

    private static final int THREADS = 6;

    @Autowired BranchLikeService branchLikeService;
    @Autowired TransactionTemplate transactionTemplate;

    @PersistenceContext EntityManager em;

    private Long userId;
    private Long branchId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            User user = TestFixtures.user("likeRace");
            em.persist(user);
            Branch branch = TestFixtures.branch("찜경합점");
            em.persist(branch);
            userId = user.getId();
            branchId = branch.getId();
        });
    }

    // 커밋하는 테스트라 롤백에 기댈 수 없다. 남기면 다른 테스트의 목록 단언이 깨진다.
    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            em.createQuery("delete from BranchLike").executeUpdate();
            em.createQuery("delete from Branch").executeUpdate();
            em.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    void 같은_찜이_동시에_들어와도_행은_하나만_남는다() throws Exception {
        List<Throwable> failures = new ArrayList<>();
        int successes = 0;
        for (Throwable result : runConcurrently()) {
            if (result == null) {
                successes++;
            } else {
                failures.add(result);
            }
        }

        assertThat(successes).isGreaterThanOrEqualTo(1);
        assertThat(failures).allSatisfy(t -> {
            assertThat(t).isInstanceOf(CustomException.class);
            assertThat(((CustomException) t).getErrorCode()).isEqualTo(ErrorCode.LIKE_REQUEST_CONFLICT);
        });
        assertThat(likeCount()).isEqualTo(1);
    }

    @Test
    void 경합에서_진_요청도_재시도하면_성공한다() throws Exception {
        runConcurrently();

        branchLikeService.like(branchId, userId);

        assertThat(likeCount()).isEqualTo(1);
    }

    // 성공은 null, 실패는 던져진 예외로 돌려준다.
    private List<Throwable> runConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);

        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        branchLikeService.like(branchId, userId);
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private long likeCount() {
        return transactionTemplate.execute(status -> em.createQuery(
                "select count(bl) from BranchLike bl", Long.class).getSingleResult());
    }
}
