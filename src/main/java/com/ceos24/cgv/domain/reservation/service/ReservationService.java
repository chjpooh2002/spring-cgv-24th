package com.ceos24.cgv.domain.reservation.service;

import com.ceos24.cgv.domain.branch.entity.TheaterType;
import com.ceos24.cgv.global.common.PaymentResult;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.domain.reservation.dto.PaymentRequest;
import com.ceos24.cgv.domain.reservation.dto.ReservationCreateRequest;
import com.ceos24.cgv.domain.reservation.dto.ReservationDetailRow;
import com.ceos24.cgv.domain.reservation.dto.ReservationResponse;
import com.ceos24.cgv.domain.reservation.entity.Reservation;
import com.ceos24.cgv.domain.reservation.entity.ReservationStatus;
import com.ceos24.cgv.domain.reservation.repository.ReservationRepository;
import com.ceos24.cgv.domain.reservation.repository.ReservationSeatRepository;
import com.ceos24.cgv.domain.screening.entity.Screening;
import com.ceos24.cgv.domain.screening.repository.ScreeningRepository;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ScreeningRepository screeningRepository;
    private final UserRepository userRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final Clock clock;

    @Transactional
    public ReservationResponse create(Long userId, ReservationCreateRequest req) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 1. 회차 존재 (응답이 읽는 영화·상영관·지점까지 함께 로딩)
        Screening screening = screeningRepository.findByIdWithDetails(req.screeningId())
                .orElseThrow(() -> new CustomException(ErrorCode.SCREENING_NOT_FOUND));

        // 2. 사용자 존재. 토큰은 만료 전까지 유효하므로 탈퇴한 사용자의 토큰도 여기까지 온다
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 3. 좌석 범위 검증
        TheaterType type = screening.getTheater().getTheaterType();
        for (ReservationCreateRequest.SeatRequest s : req.seats()) {
            if (!type.isValidSeat(s.rowNum(), s.colNum())) {
                throw new CustomException(ErrorCode.SEAT_OUT_OF_RANGE);
            }
        }

        // 4. 요청 내 중복 검증
        long distinctCount = req.seats().stream()
                .map(s -> List.of(s.rowNum(), s.colNum()))
                .distinct()
                .count();
        if (distinctCount != req.seats().size()) {
            throw new CustomException(ErrorCode.DUPLICATE_SEAT_IN_REQUEST);
        }

        // 5. 만료된 선점 정리. 유니크 인덱스는 만료 시각을 모르므로,
        //    행을 놓아주지 않으면 시간이 지난 좌석도 다시 선택할 수 없다.
        //    범위·중복 검증을 통과한 뒤라 좌석 키가 안전한 범위 안이다.
        releaseExpiredHolds(screening.getId(), req.seats(), now);

        // 6. 이미 점유된 좌석 pre-check. 경쟁이 없는 경우에 친절한 응답을 주기 위한 것이고,
        //    검사와 INSERT 사이의 틈은 7번의 유니크 제약이 막는다.
        Set<String> taken = reservationSeatRepository
                .findOccupiedPositionsByScreeningId(screening.getId(), ReservationStatus.PENDING, now).stream()
                .map(p -> p.getRowNum() + ":" + p.getColNum())
                .collect(Collectors.toSet());
        for (ReservationCreateRequest.SeatRequest s : req.seats()) {
            if (taken.contains(s.rowNum() + ":" + s.colNum())) {
                throw new CustomException(ErrorCode.SEAT_ALREADY_RESERVED);
            }
        }

        // 7. 선점 생성 + 경쟁 상태 안전망 (동시 요청으로 유니크 제약 위반 시 포착)
        Reservation reservation = Reservation.builder()
                .user(user).screening(screening).now(now).build();
        int basePrice = screening.getPrice();
        // 좌석 순서가 곧 INSERT 순서이고, INSERT 순서가 곧 락 획득 순서다.
        // 정렬해 두면 [A1,A2]와 [A2,A1] 요청이 서로를 물고 도는 데드락이 생길 수 없다.
        orderedSeats(req).forEach(s -> reservation.addSeat(s.rowNum(), s.colNum(), s.audienceType(), basePrice));

        try {
            return ReservationResponse.from(reservationRepository.saveAndFlush(reservation), now);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.SEAT_ALREADY_RESERVED);
        } catch (ConcurrencyFailureException e) {
            // 데드락·락 대기 타임아웃. 좌석이 팔렸다는 뜻이 아니라 판정하지 못했다는 뜻이라
            // 이미 선택된 좌석과 구분해서 재시도를 안내한다.
            throw new CustomException(ErrorCode.SEAT_RESERVATION_CONFLICT);
        }
    }

    // 결제 실패는 예외로 알리지만 좌석 해제는 남아야 하므로 롤백 대상에서 뺀다.
    @Transactional(noRollbackFor = CustomException.class)
    public ReservationResponse pay(Long id, Long userId, PaymentRequest req) {
        LocalDateTime now = LocalDateTime.now(clock);
        Reservation reservation = findOwnedWithDetails(id, userId);

        if (req.result() == PaymentResult.FAILURE) {
            reservation.cancel(now);
            throw new CustomException(ErrorCode.PAYMENT_FAILED);
        }

        reservation.confirm(now);
        return ReservationResponse.from(reservation, now);
    }

    // 조회는 상태를 바꾸지 않으므로 엔티티가 필요 없다. 프로젝션으로 받으면 응답이 쓰는
    // 스칼라만 읽고, 영속성 컨텍스트에 엔티티와 더티 체킹 스냅샷도 남지 않는다.
    public ReservationResponse getById(Long id, Long userId) {
        List<ReservationDetailRow> rows = reservationRepository.findOwnedDetailRows(id, userId);
        if (rows.isEmpty()) {
            throw new CustomException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        return ReservationResponse.of(rows, LocalDateTime.now(clock));
    }

    // 취소·만료된 예매도 상태를 달아 보여준다. 사라지면 사용자는 취소가 됐는지 확인할 곳이 없다.
    // 탈퇴한 사용자의 토큰을 빈 목록으로 받아 주면 없는 사용자가 "예매 없음"으로 숨는다.
    public List<ReservationResponse> findMine(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        return ReservationResponse.listOf(
                reservationRepository.findDetailRowsByUserId(userId), LocalDateTime.now(clock));
    }

    @Transactional
    public void cancel(Long id, Long userId) {
        findOwnedWithSeats(id, userId).cancel(LocalDateTime.now(clock));
    }

    // 결제는 상태를 바꿔야 해서 관리 상태 엔티티가 필요하다. 응답까지 한 쿼리로 만들려고
    // 영화·지점을 함께 가져온다.
    // 남의 예매도 RESERVATION_NOT_FOUND다. 403이나 별도 코드를 주면 순차 id를 넣어 보며
    // 어떤 예매가 존재하는지 알아낼 수 있다. 조회 단계에서 걸러지므로 상태 변경 메서드에 닿지 않는다.
    private Reservation findOwnedWithDetails(Long id, Long userId) {
        return reservationRepository.findOwnedWithDetails(id, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESERVATION_NOT_FOUND));
    }

    private Reservation findOwnedWithSeats(Long id, Long userId) {
        return reservationRepository.findOwnedWithSeats(id, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.RESERVATION_NOT_FOUND));
    }

    // 벌크 UPDATE라 INSERT보다 먼저 DB에 반영된다. 엔티티로 바꿔 한 번에 flush하면 Hibernate가 INSERT를 앞세운다.
    // 같은 만료 선점을 다른 요청이 먼저 풀었으면 예매 UPDATE가 0행이 되고 좌석도 건드리지 않는다.
    // 예매와 좌석 중 한쪽만 이 트랜잭션의 새 버전이 되는 일을 막기 위해서다(expireIfPending 주석).
    private void releaseExpiredHolds(Long screeningId,
                                     List<ReservationCreateRequest.SeatRequest> seats,
                                     LocalDateTime now) {
        List<Long> expiredIds = reservationRepository.findExpiredHoldsBlocking(
                screeningId, seatKeysOf(seats), ReservationStatus.PENDING, now);
        for (Long id : expiredIds) {
            if (reservationRepository.expireIfPending(id, ReservationStatus.PENDING, ReservationStatus.EXPIRED, now) == 1) {
                reservationSeatRepository.releaseOccupied(id, now);
            }
        }
    }

    // (행, 열) 쌍을 IN 절에 넣을 방법이 DB마다 달라 스칼라 하나로 접는다.
    // 열 수는 상영관 종류 최대가 22라 100진 자리에서 겹치지 않는다.
    private List<Integer> seatKeysOf(List<ReservationCreateRequest.SeatRequest> seats) {
        return seats.stream()
                .map(s -> s.rowNum() * 100 + s.colNum())
                .toList();
    }

    private List<ReservationCreateRequest.SeatRequest> orderedSeats(ReservationCreateRequest req) {
        return req.seats().stream()
                .sorted(Comparator.comparingInt(ReservationCreateRequest.SeatRequest::rowNum)
                        .thenComparingInt(ReservationCreateRequest.SeatRequest::colNum))
                .toList();
    }
}
