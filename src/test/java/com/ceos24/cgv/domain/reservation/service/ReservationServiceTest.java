package com.ceos24.cgv.domain.reservation.service;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.branch.entity.Theater;
import com.ceos24.cgv.domain.branch.entity.TheaterType;
import com.ceos24.cgv.global.common.PaymentResult;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.domain.movie.entity.Movie;
import com.ceos24.cgv.domain.reservation.dto.PaymentRequest;
import com.ceos24.cgv.domain.reservation.entity.AudienceType;
import com.ceos24.cgv.domain.reservation.entity.Reservation;
import com.ceos24.cgv.domain.reservation.repository.ReservationRepository;
import com.ceos24.cgv.domain.reservation.repository.ReservationSeatRepository;
import com.ceos24.cgv.domain.reservation.repository.ReservationSeatRepository.SeatPositionProjection;
import com.ceos24.cgv.domain.reservation.entity.ReservationSeat;
import com.ceos24.cgv.domain.reservation.entity.ReservationStatus;
import com.ceos24.cgv.domain.reservation.dto.ReservationCreateRequest;
import com.ceos24.cgv.domain.reservation.dto.ReservationDetailRow;
import com.ceos24.cgv.domain.reservation.dto.ReservationResponse;
import com.ceos24.cgv.domain.screening.entity.Screening;
import com.ceos24.cgv.domain.screening.repository.ScreeningRepository;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 1, 9, 0);
    private static final LocalDateTime START = LocalDateTime.of(2024, 6, 1, 10, 0);

    @Mock ReservationRepository reservationRepository;
    @Mock ScreeningRepository screeningRepository;
    @Mock UserRepository userRepository;
    @Mock ReservationSeatRepository reservationSeatRepository;

    ReservationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new ReservationService(reservationRepository, screeningRepository,
                userRepository, reservationSeatRepository, clock);
    }

    // ─── create ───────────────────────────────────────────────────────────────

    @Test
    void 없는_회차면_SCREENING_NOT_FOUND() {
        given(screeningRepository.findByIdWithDetails(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(1L, reqOf(1L, new int[]{1, 1})))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SCREENING_NOT_FOUND);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void 없는_사용자면_USER_NOT_FOUND() {
        given(screeningRepository.findByIdWithDetails(1L)).willReturn(Optional.of(screeningWith(1L, 14000, START)));
        given(userRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(99L, reqOf(1L, new int[]{1, 1})))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 좌석이_범위를_벗어나면_SEAT_OUT_OF_RANGE() {
        givenScreeningAndUser(1L, 1L);

        // STANDARD rowCount=8 인 상영관에 row=9 요청
        assertThatThrownBy(() -> service.create(1L, reqOf(1L, new int[]{9, 1})))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_OUT_OF_RANGE);
        verify(reservationSeatRepository, never()).findOccupiedPositionsByScreeningId(any(), any(), any());
    }

    @Test
    void 요청_내_좌석이_중복이면_DUPLICATE_SEAT_IN_REQUEST() {
        givenScreeningAndUser(1L, 1L);

        assertThatThrownBy(() -> service.create(1L, reqOf(1L, new int[]{1, 1}, new int[]{1, 1})))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DUPLICATE_SEAT_IN_REQUEST);
        verify(reservationSeatRepository, never()).findOccupiedPositionsByScreeningId(any(), any(), any());
    }

    @Test
    void 이미_점유된_좌석이면_SEAT_ALREADY_RESERVED_pre_check() {
        givenScreeningAndUser(1L, 1L);
        // 스텁 체인 안에서 다른 목을 스텁하면 Mockito가 미완성 스텁으로 본다
        List<SeatPositionProjection> occupied = List.of(positionOf(1, 1));
        given(reservationSeatRepository.findOccupiedPositionsByScreeningId(1L, ReservationStatus.PENDING, NOW))
                .willReturn(occupied);

        assertThatThrownBy(() -> service.create(1L, reqOf(1L, new int[]{1, 1})))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_ALREADY_RESERVED);
        verify(reservationRepository, never()).saveAndFlush(any());
    }

    @Test
    void 동시_요청_유니크제약_충돌시_SEAT_ALREADY_RESERVED() {
        givenScreeningAndUser(1L, 1L);
        given(reservationRepository.saveAndFlush(any()))
                .willThrow(new DataIntegrityViolationException("uk_seat_screening_row_col_release"));

        assertThatThrownBy(() -> service.create(1L, reqOf(1L, new int[]{1, 1})))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_ALREADY_RESERVED);
    }

    @Test
    void 좌석_선점은_PENDING으로_생성되고_만료시각이_붙는다() {
        givenScreeningAndUser(1L, 2L);
        givenSaveAssignsId(10L);

        // 정렬 검증을 위해 순서를 뒤섞어 요청 (B3 → A2 → A1)
        ReservationResponse response = service.create(2L,
                reqOf(1L, new int[]{2, 3}, new int[]{1, 2}, new int[]{1, 1}));

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.expiresAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(response.confirmedAt()).isNull();
        assertThat(response.seats()).extracting(ReservationResponse.SeatSummary::label)
                .containsExactly("A1", "A2", "B3");
        assertThat(response.totalPrice()).isEqualTo(14000 * 3);
    }

    @Test
    void 권종별_할인가가_좌석에_스냅샷된다() {
        givenScreeningAndUser(1L, 1L);
        givenSaveAssignsId(1L);

        service.create(1L, new ReservationCreateRequest(1L, List.of(
                new ReservationCreateRequest.SeatRequest(1, 1, AudienceType.ADULT),
                new ReservationCreateRequest.SeatRequest(1, 2, AudienceType.YOUTH),
                new ReservationCreateRequest.SeatRequest(1, 3, AudienceType.SENIOR))));

        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSeats()).extracting(ReservationSeat::getPaidPrice)
                .containsExactly(14000, 11200, 7000);
        assertThat(captor.getValue().getTotalPrice()).isEqualTo(32200);
    }

    @Test
    void 만료된_선점은_좌석을_잡기_전에_정리된다() {
        givenScreeningAndUser(1L, 1L);
        givenSaveAssignsId(2L);
        given(reservationRepository.findExpiredHoldsBlocking(
                eq(1L), anyList(), eq(ReservationStatus.PENDING), eq(NOW)))
                .willReturn(List.of(7L));
        given(reservationRepository.expireIfPending(7L, ReservationStatus.PENDING, ReservationStatus.EXPIRED, NOW))
                .willReturn(1);

        service.create(1L, reqOf(1L, new int[]{1, 1}));

        // 해제가 새 좌석 INSERT보다 먼저 나가야 유니크 제약에 걸리지 않는다
        InOrder order = inOrder(reservationRepository, reservationSeatRepository);
        order.verify(reservationRepository).expireIfPending(7L, ReservationStatus.PENDING, ReservationStatus.EXPIRED, NOW);
        order.verify(reservationSeatRepository).releaseOccupied(7L, NOW);
        order.verify(reservationRepository).saveAndFlush(any());
    }

    // 다른 요청이 먼저 만료시킨 선점이다. 좌석까지 같은 값으로 다시 쓰면 이 트랜잭션의 스냅샷에 예매와 좌석이 엇갈려 보인다.
    @Test
    void 다른_요청이_먼저_만료시킨_선점의_좌석은_건드리지_않는다() {
        givenScreeningAndUser(1L, 1L);
        givenSaveAssignsId(2L);
        given(reservationRepository.findExpiredHoldsBlocking(
                eq(1L), anyList(), eq(ReservationStatus.PENDING), eq(NOW)))
                .willReturn(List.of(7L));
        given(reservationRepository.expireIfPending(7L, ReservationStatus.PENDING, ReservationStatus.EXPIRED, NOW))
                .willReturn(0);

        service.create(1L, reqOf(1L, new int[]{1, 1}));

        verify(reservationSeatRepository, never()).releaseOccupied(anyLong(), any());
        verify(reservationRepository).saveAndFlush(any());
    }

    @Test
    void 정리_대상은_요청한_좌석을_막고_있는_선점으로_좁힌다() {
        givenScreeningAndUser(1L, 1L);
        givenSaveAssignsId(2L);

        service.create(1L, reqOf(1L, new int[]{2, 3}, new int[]{1, 5}));

        // 회차의 만료 선점을 전부 풀면 그 행들에 UPDATE 락이 걸려, 같은 회차를 골랐을 뿐인
        // 다른 좌석 요청까지 서로를 기다린다. 요청한 좌석만 키로 넘긴다.
        ArgumentCaptor<List<Integer>> captor = ArgumentCaptor.forClass(List.class);
        verify(reservationRepository).findExpiredHoldsBlocking(
                eq(1L), captor.capture(), eq(ReservationStatus.PENDING), eq(NOW));
        assertThat(captor.getValue()).containsExactlyInAnyOrder(203, 105);
    }

    @Test
    void 좌석은_행_열_순서로_INSERT된다() {
        givenScreeningAndUser(1L, 1L);
        givenSaveAssignsId(2L);

        service.create(1L, reqOf(1L, new int[]{2, 3}, new int[]{1, 5}, new int[]{2, 1}));

        // INSERT 순서가 곧 락 획득 순서다. 모든 요청이 같은 순서를 쓰면 데드락이 생길 수 없다.
        ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getSeats()).extracting(ReservationSeat::getLabel)
                .containsExactly("A5", "B1", "B3");
    }

    @Test
    void 데드락이나_락_타임아웃은_SEAT_RESERVATION_CONFLICT로_바뀐다() {
        givenScreeningAndUser(1L, 1L);
        given(reservationRepository.saveAndFlush(any(Reservation.class)))
                .willThrow(new CannotAcquireLockException("lock wait timeout"));

        assertThatThrownBy(() -> service.create(1L, reqOf(1L, new int[]{1, 1})))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SEAT_RESERVATION_CONFLICT);
    }

    // ─── pay ──────────────────────────────────────────────────────────────────

    @Test
    void 결제_성공이면_RESERVED로_확정된다() {
        Reservation hold = holdWithId(1L, NOW, 1, 1);
        given(reservationRepository.findOwnedWithDetails(1L, 2L)).willReturn(Optional.of(hold));

        ReservationResponse response = service.pay(1L, 2L, new PaymentRequest(PaymentResult.SUCCESS));

        assertThat(response.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(hold.getConfirmedAt()).isEqualTo(NOW);
        assertThat(hold.getSeats()).allMatch(ReservationSeat::isOccupied);
    }

    @Test
    void 결제_실패면_좌석이_풀리고_PAYMENT_FAILED() {
        Reservation hold = holdWithId(1L, NOW, 1, 1);
        given(reservationRepository.findOwnedWithDetails(1L, 2L)).willReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.pay(1L, 2L, new PaymentRequest(PaymentResult.FAILURE)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_FAILED);

        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(hold.getSeats()).noneMatch(ReservationSeat::isOccupied);
    }

    @Test
    void 만료된_선점을_결제하면_RESERVATION_EXPIRED() {
        Reservation hold = holdWithId(1L, NOW.minusMinutes(20), 1, 1);
        given(reservationRepository.findOwnedWithDetails(1L, 2L)).willReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.pay(1L, 2L, new PaymentRequest(PaymentResult.SUCCESS)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_EXPIRED);
        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    void 이미_확정된_예매를_다시_결제하면_RESERVATION_NOT_PENDING() {
        Reservation hold = holdWithId(1L, NOW, 1, 1);
        hold.confirm(NOW);
        given(reservationRepository.findOwnedWithDetails(1L, 2L)).willReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.pay(1L, 2L, new PaymentRequest(PaymentResult.SUCCESS)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_NOT_PENDING);
    }

    @Test
    void 남의_예매를_결제하면_RESERVATION_NOT_FOUND() {
        // 소유자 조건이 붙은 조회가 비므로 엔티티가 로딩되지 않는다. 확정도 해제도 일어날 수 없다
        given(reservationRepository.findOwnedWithDetails(1L, 3L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(1L, 3L, new PaymentRequest(PaymentResult.FAILURE)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
    }

    // ─── getById ──────────────────────────────────────────────────────────────

    @Test
    void 없는_예매_조회시_RESERVATION_NOT_FOUND() {
        given(reservationRepository.findOwnedDetailRows(99L, 2L)).willReturn(List.of());

        assertThatThrownBy(() -> service.getById(99L, 2L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    void 예매_조회_정상() {
        given(reservationRepository.findOwnedDetailRows(5L, 2L))
                .willReturn(List.of(detailRow(5L, NOW.plusMinutes(10), ReservationStatus.PENDING, 1, 1),
                        detailRow(5L, NOW.plusMinutes(10), ReservationStatus.PENDING, 1, 2)));

        ReservationResponse response = service.getById(5L, 2L);

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.userId()).isEqualTo(2L);
        assertThat(response.seats()).extracting(ReservationResponse.SeatSummary::label)
                .containsExactly("A1", "A2");
        assertThat(response.totalPrice()).isEqualTo(28000);
        assertThat(response.status()).isEqualTo(ReservationStatus.PENDING);
        assertThat(response.screening().movieTitle()).isEqualTo("범죄도시4");
        assertThat(response.screening().branchName()).isEqualTo("강남점");
    }

    @Test
    void 만료된_선점은_조회하면_EXPIRED로_보인다() {
        // DB 상태는 아직 PENDING이지만 이미 좌석을 놓은 것이나 마찬가지다
        given(reservationRepository.findOwnedDetailRows(5L, 2L))
                .willReturn(List.of(detailRow(5L, NOW.minusMinutes(10), ReservationStatus.PENDING, 1, 1)));

        assertThat(service.getById(5L, 2L).status()).isEqualTo(ReservationStatus.EXPIRED);
    }

    // ─── cancel ───────────────────────────────────────────────────────────────

    @Test
    void 없는_예매_취소시_RESERVATION_NOT_FOUND() {
        given(reservationRepository.findOwnedWithSeats(99L, 2L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(99L, 2L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
    }

    @Test
    void 취소해도_어느_좌석을_얼마에_잡았는지가_남는다() {
        Reservation hold = holdWithId(1L, NOW, 1, 1, 1, 2);
        given(reservationRepository.findOwnedWithSeats(1L, 2L)).willReturn(Optional.of(hold));

        service.cancel(1L, 2L);

        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(hold.getCancelledAt()).isEqualTo(NOW);
        assertThat(hold.getSeats()).hasSize(2);
        assertThat(hold.getTotalPrice()).isEqualTo(28000);
        assertThat(hold.getSeats()).noneMatch(ReservationSeat::isOccupied);
    }

    @Test
    void 이미_취소된_예매_취소시_ALREADY_CANCELLED() {
        Reservation hold = holdWithId(1L, NOW, 1, 1);
        hold.cancel(NOW);
        given(reservationRepository.findOwnedWithSeats(1L, 2L)).willReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.cancel(1L, 2L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ALREADY_CANCELLED);
    }

    @Test
    void 상영_20분_이내면_확정된_예매를_취소할_수_없다() {
        Reservation hold = holdWithId(1L, NOW, NOW.plusMinutes(19), 1, 1);
        hold.confirm(NOW);
        given(reservationRepository.findOwnedWithSeats(1L, 2L)).willReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.cancel(1L, 2L))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CANCEL_DEADLINE_PASSED);
        assertThat(hold.getSeats()).allMatch(ReservationSeat::isOccupied);
    }

    @Test
    void 선점은_상영_20분_이내여도_놓을_수_있다() {
        Reservation hold = holdWithId(1L, NOW, NOW.plusMinutes(19), 1, 1);
        given(reservationRepository.findOwnedWithSeats(1L, 2L)).willReturn(Optional.of(hold));

        service.cancel(1L, 2L);

        assertThat(hold.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    // ─── 픽스처 헬퍼 ──────────────────────────────────────────────────────────

    private void givenScreeningAndUser(long screeningId, long userId) {
        given(screeningRepository.findByIdWithDetails(screeningId))
                .willReturn(Optional.of(screeningWith(screeningId, 14000, START)));
        given(userRepository.findById(userId)).willReturn(Optional.of(userWithId(userId)));
    }

    private void givenSaveAssignsId(long id) {
        given(reservationRepository.saveAndFlush(any())).willAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "id", id);
            return r;
        });
    }

    private Reservation holdWithId(long id, LocalDateTime createdAt, int... rowCols) {
        return holdWithId(id, createdAt, START, rowCols);
    }

    private Reservation holdWithId(long id, LocalDateTime createdAt, LocalDateTime startAt, int... rowCols) {
        Reservation reservation = Reservation.builder()
                .user(userWithId(2L))
                .screening(screeningWith(1L, 14000, startAt))
                .now(createdAt)
                .build();
        ReflectionTestUtils.setField(reservation, "id", id);
        for (int i = 0; i < rowCols.length; i += 2) {
            reservation.addSeat(rowCols[i], rowCols[i + 1], AudienceType.ADULT, 14000);
        }
        return reservation;
    }

    private ReservationDetailRow detailRow(long id, LocalDateTime expiresAt, ReservationStatus status,
                                          int rowNum, int colNum) {
        return new ReservationDetailRow(
                id, 2L, status, NOW, expiresAt, null, null,
                1L, "범죄도시4", "1관", "강남점", START, START.plusMinutes(120),
                rowNum, colNum, AudienceType.ADULT, 14000);
    }

    private Screening screeningWith(long id, int price, LocalDateTime startAt) {
        Branch branch = Branch.builder().name("강남점").address("서울 강남구").build();
        Theater theater = Theater.builder().branch(branch).theaterType(TheaterType.STANDARD).name("1관").build();
        Movie movie = Movie.builder()
                .title("범죄도시4").director("감독").genre("액션")
                .runningTime(120).releaseDate(LocalDate.of(2024, 1, 1)).ageRating("15세").build();
        Screening screening = Screening.builder()
                .theater(theater).movie(movie)
                .startAt(startAt).endAt(startAt.plusMinutes(120)).price(price)
                .build();
        ReflectionTestUtils.setField(screening, "id", id);
        return screening;
    }

    private User userWithId(long id) {
        User user = User.builder()
                .loginId("testuser01").password("pw").name("테스트유저")
                .birthDate(LocalDate.of(2000, 1, 1))
                .email("testuser01@test.com").phoneNumber("01012345678").build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private SeatPositionProjection positionOf(int row, int col) {
        SeatPositionProjection projection = mock(SeatPositionProjection.class);
        given(projection.getRowNum()).willReturn(row);
        given(projection.getColNum()).willReturn(col);
        return projection;
    }

    private ReservationCreateRequest reqOf(long screeningId, int[]... seats) {
        List<ReservationCreateRequest.SeatRequest> seatRequests = Arrays.stream(seats)
                .map(s -> new ReservationCreateRequest.SeatRequest(s[0], s[1], AudienceType.ADULT))
                .toList();
        return new ReservationCreateRequest(screeningId, seatRequests);
    }
}
