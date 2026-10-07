package com.ceos24.cgv.domain.reservation.service;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.branch.entity.Theater;
import com.ceos24.cgv.domain.branch.entity.TheaterType;
import com.ceos24.cgv.domain.movie.entity.Movie;
import com.ceos24.cgv.domain.reservation.dto.ReservationCreateRequest;
import com.ceos24.cgv.domain.reservation.entity.AudienceType;
import com.ceos24.cgv.domain.reservation.entity.Reservation;
import com.ceos24.cgv.domain.reservation.entity.ReservationSeat;
import com.ceos24.cgv.domain.reservation.entity.ReservationStatus;
import com.ceos24.cgv.domain.screening.entity.Screening;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

// 동시성을 유니크 제약 하나에 맡기고 있으므로, 경합이 붙었을 때 좌석이 겹치지 않는지와
// 모든 실패가 매핑된 예외로 나오는지를 실제 스레드로 확인한다.
// 스레드마다 트랜잭션이 따로 열려야 해서 ControllerIntegrationTest(@Transactional)를 쓰지 않는다.
@SpringBootTest
@Import(MySqlContainerConfig.class)
class ReservationConcurrencyTest {

    private static final int PRICE = 14000;
    private static final int USER_COUNT = 6;

    @Autowired ReservationService reservationService;
    @Autowired TransactionTemplate transactionTemplate;

    @PersistenceContext EntityManager em;

    private Long screeningId;
    private List<Long> userIds;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            Branch branch = TestFixtures.branch("동시성지점");
            em.persist(branch);
            Theater theater = TestFixtures.theater(branch, TheaterType.IMAX, "1관");
            em.persist(theater);
            Movie movie = TestFixtures.movie("동시성영화");
            em.persist(movie);
            Screening screening = TestFixtures.screening(
                    theater, movie, LocalDateTime.now().plusDays(1), PRICE);
            em.persist(screening);

            screeningId = screening.getId();
            userIds = new ArrayList<>();
            for (int i = 0; i < USER_COUNT; i++) {
                User user = TestFixtures.user("race" + i);
                em.persist(user);
                userIds.add(user.getId());
            }
        });
    }

    // 커밋하는 테스트라 롤백에 기댈 수 없다. 남기면 다른 테스트의 목록 단언이 깨진다.
    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            em.createQuery("delete from ReservationSeat").executeUpdate();
            em.createQuery("delete from Reservation").executeUpdate();
            em.createQuery("delete from Screening").executeUpdate();
            em.createQuery("delete from Theater").executeUpdate();
            em.createQuery("delete from Branch").executeUpdate();
            em.createQuery("delete from Movie").executeUpdate();
            em.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    void 같은_좌석에_동시에_몰려도_한_건만_성공한다() throws Exception {
        List<Outcome> outcomes = runConcurrently(USER_COUNT,
                i -> request(userIds.get(i), seat(3, 5)));

        assertThat(successCount(outcomes)).isEqualTo(1);
        assertThatFailuresAreMapped(outcomes);
        assertThat(occupiedSeatCount()).isEqualTo(1);
    }

    @Test
    void 좌석_순서가_엇갈린_요청이_겹쳐도_매핑되지_않은_예외가_나오지_않는다() throws Exception {
        // 정렬 없이 INSERT하면 A1→A2 와 A2→A1 이 서로를 물고 도는 데드락이 된다.
        // 데드락은 DataIntegrityViolationException이 아니라 ConcurrencyFailureException이라
        // 예전 catch 하나로는 잡히지 않고 그대로 새어나갔다.
        List<Outcome> outcomes = runConcurrently(USER_COUNT, i -> i % 2 == 0
                ? request(userIds.get(i), seat(1, 1), seat(1, 2))
                : request(userIds.get(i), seat(1, 2), seat(1, 1)));

        assertThat(successCount(outcomes)).isEqualTo(1);
        assertThatFailuresAreMapped(outcomes);
        assertThat(occupiedSeatCount()).isEqualTo(2);
    }

    @Test
    void 만료된_선점이_잡고_있던_좌석을_동시_요청이_다시_가져간다() throws Exception {
        LocalDateTime longAgo = LocalDateTime.now().minusMinutes(30);
        transactionTemplate.executeWithoutResult(status -> {
            Screening screening = em.find(Screening.class, screeningId);
            User user = em.find(User.class, userIds.get(0));
            Reservation stale = TestFixtures.hold(user, screening, longAgo);
            stale.addSeat(2, 1, AudienceType.ADULT);
            stale.addSeat(2, 2, AudienceType.ADULT);
            em.persist(stale);
        });

        // 정리가 별도 트랜잭션에서 커밋된 뒤 INSERT가 뒤따라야 한다.
        // 순서가 뒤집히면 풀리지 않은 행 때문에 여기서 유니크 제약에 걸린다.
        List<Outcome> outcomes = runConcurrently(2, i ->
                request(userIds.get(i + 1), seat(2, i + 1)));

        assertThat(successCount(outcomes)).isEqualTo(2);
        assertThat(occupiedSeatCount()).isEqualTo(2);

        transactionTemplate.executeWithoutResult(status -> {
            Reservation expired = em.createQuery(
                            "select r from Reservation r join fetch r.seats where r.status = :status",
                            Reservation.class)
                    .setParameter("status", ReservationStatus.EXPIRED)
                    .getSingleResult();
            assertThat(expired.getSeats()).noneMatch(ReservationSeat::isOccupied);
        });
    }

    // 위 테스트의 간헐 실패를 순서를 고정해 다시 만든 것이다. B의 스냅샷이 A의 커밋보다 앞서고, A가 만료 선점을 먼저 풀었다.
    // 이전 구현은 B도 같은 값으로 다시 UPDATE했고, 시각까지 같으면 좌석 행만 옛 버전으로 남아 B가 (2,2)를 점유로 봤다.
    // 시각이 같아지는 것은 여기서 고정할 수 없어 이 테스트만으로 이전 구현이 늘 실패하지는 않는다. 확인하는 것은 B의 해제가
    // 0행이 되는 경로에서도 빈 좌석을 잡는다는 점이다.
    @Test
    void 만료_선점을_다른_요청이_먼저_풀어도_스냅샷이_앞선_요청은_빈_좌석을_잡는다() throws Exception {
        transactionTemplate.executeWithoutResult(status -> {
            Reservation stale = TestFixtures.hold(
                    em.find(User.class, userIds.get(0)), em.find(Screening.class, screeningId),
                    LocalDateTime.now().minusMinutes(30));
            stale.addSeat(2, 1, AudienceType.ADULT);
            stale.addSeat(2, 2, AudienceType.ADULT);
            em.persist(stale);
        });

        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // B의 스냅샷을 만든다. 이후 create는 이 트랜잭션에 합류한다.
                em.createQuery("select count(r) from Reservation r", Long.class).getSingleResult();
                try {
                    other.submit(() -> reservationService.create(userIds.get(1), request(userIds.get(1), seat(2, 1)).request()))
                            .get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                reservationService.create(userIds.get(2), request(userIds.get(2), seat(2, 2)).request());
            });
        } finally {
            other.shutdownNow();
        }

        assertThat(occupiedSeatCount()).isEqualTo(2);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private List<Outcome> runConcurrently(int threads,
                                          IntFunction<Attempt> attemptOf)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Attempt attempt = attemptOf.apply(i);
            tasks.add(() -> {
                ready.countDown();
                go.await();
                try {
                    reservationService.create(attempt.userId(), attempt.request());
                    return new Outcome(true, null);
                } catch (Throwable t) {
                    return new Outcome(false, t);
                }
            });
        }

        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<Outcome> task : tasks) {
                futures.add(pool.submit(task));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();

            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertThatFailuresAreMapped(List<Outcome> outcomes) {
        List<Throwable> failures = outcomes.stream()
                .filter(o -> !o.success())
                .map(Outcome::error)
                .toList();

        assertThat(failures).isNotEmpty();
        assertThat(failures).allSatisfy(t -> {
            assertThat(t).isInstanceOf(CustomException.class);
            assertThat(((CustomException) t).getErrorCode())
                    .isIn(ErrorCode.SEAT_ALREADY_RESERVED, ErrorCode.SEAT_RESERVATION_CONFLICT);
        });
    }

    private long successCount(List<Outcome> outcomes) {
        return outcomes.stream().filter(Outcome::success).count();
    }

    private long occupiedSeatCount() {
        return transactionTemplate.execute(status -> em.createQuery(
                        "select count(rs) from ReservationSeat rs where rs.releaseKey = 0", Long.class)
                .getSingleResult());
    }

    private Attempt request(Long userId, ReservationCreateRequest.SeatRequest... seats) {
        return new Attempt(userId, new ReservationCreateRequest(screeningId, List.of(seats)));
    }

    private ReservationCreateRequest.SeatRequest seat(int row, int col) {
        return new ReservationCreateRequest.SeatRequest(row, col, AudienceType.ADULT);
    }

    private record Attempt(Long userId, ReservationCreateRequest request) {}

    private record Outcome(boolean success, Throwable error) {}
}
