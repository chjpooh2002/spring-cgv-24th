package com.ceos24.cgv.domain.reservation.repository;

import com.ceos24.cgv.domain.reservation.dto.ReservationDetailRow;
import com.ceos24.cgv.domain.reservation.entity.Reservation;
import com.ceos24.cgv.domain.reservation.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    // 아래 단건 조회 셋은 모두 소유자 조건을 쿼리에 건다. 남의 예매는 로딩되지 않아 상태를 바꿀
    // 엔티티 자체가 없고, 없는 예매와 똑같이 빈 결과가 된다. r.user.id는 FK 컬럼이라 조인이 늘지 않는다.

    // 응답은 사용자를 id로만 쓴다. 프록시의 id getter는 초기화 없이 식별자를 돌려주므로
    // user는 조인하지 않는다. 이름 같은 다른 필드를 응답에 실으면 여기에 fetch join을 더해야 한다.
    @Query("""
            SELECT r FROM Reservation r
            JOIN FETCH r.screening s
            JOIN FETCH s.movie
            JOIN FETCH s.theater t
            JOIN FETCH t.branch
            LEFT JOIN FETCH r.seats
            WHERE r.id = :id AND r.user.id = :userId
            """)
    Optional<Reservation> findOwnedWithDetails(@Param("id") Long id, @Param("userId") Long userId);

    // 조회는 응답에 실리는 스칼라만 읽는다. 엔티티로 가져오면 branch.description(TEXT)처럼
    // 응답이 쓰지 않는 컬럼까지, 그것도 좌석 수만큼 반복해서 딸려온다.
    // 좌석은 요청 단계에서 1개 이상이 보장되므로 INNER JOIN이어도 행이 비지 않는다.
    // 빈 결과는 곧 예매가 없다는 뜻이다.
    @Query("""
            SELECT new com.ceos24.cgv.domain.reservation.dto.ReservationDetailRow(
                r.id, r.user.id, r.status, r.createdAt, r.expiresAt, r.confirmedAt, r.cancelledAt,
                s.id, m.title, t.name, b.name, s.startAt, s.endAt,
                seat.rowNum, seat.colNum, seat.audienceType, seat.paidPrice)
            FROM Reservation r
            JOIN r.screening s
            JOIN s.movie m
            JOIN s.theater t
            JOIN t.branch b
            JOIN r.seats seat
            WHERE r.id = :id AND r.user.id = :userId
            ORDER BY seat.rowNum, seat.colNum
            """)
    List<ReservationDetailRow> findOwnedDetailRows(@Param("id") Long id, @Param("userId") Long userId);

    // 내역은 예매 수 × 좌석 수 행이 된다. 엔티티로 읽으면 branch.description(TEXT)이 그 행 수만큼
    // 반복 전송되므로 단건 조회와 같은 프로젝션을 쓴다. 예매 id로 먼저 정렬해야 한 예매의 좌석이
    // 붙어 나와 순서를 유지한 채 묶을 수 있다.
    @Query("""
            SELECT new com.ceos24.cgv.domain.reservation.dto.ReservationDetailRow(
                r.id, r.user.id, r.status, r.createdAt, r.expiresAt, r.confirmedAt, r.cancelledAt,
                s.id, m.title, t.name, b.name, s.startAt, s.endAt,
                seat.rowNum, seat.colNum, seat.audienceType, seat.paidPrice)
            FROM Reservation r
            JOIN r.screening s
            JOIN s.movie m
            JOIN s.theater t
            JOIN t.branch b
            JOIN r.seats seat
            WHERE r.user.id = :userId
            ORDER BY r.id DESC, seat.rowNum, seat.colNum
            """)
    List<ReservationDetailRow> findDetailRowsByUserId(@Param("userId") Long userId);

    // 취소는 좌석 해제와 취소 기한 비교만 한다. 응답을 만들지 않으므로 사용자·영화·지점은 읽지 않는다.
    @Query("""
            SELECT r FROM Reservation r
            JOIN FETCH r.screening
            LEFT JOIN FETCH r.seats
            WHERE r.id = :id AND r.user.id = :userId
            """)
    Optional<Reservation> findOwnedWithSeats(@Param("id") Long id, @Param("userId") Long userId);

    // 요청한 좌석을 실제로 막고 있는 만료 선점만 고른다. 회차의 만료 선점을 전부 풀면
    // 그 행들에 UPDATE 락이 걸려, 같은 회차를 골랐을 뿐인 다른 좌석 요청까지 서로를 기다린다.
    // 해제는 아래 조건부 UPDATE가 하므로 엔티티가 아니라 id만 읽는다.
    @Query("""
            SELECT r.id FROM Reservation r
            WHERE r.status = :pending
              AND r.expiresAt <= :now
              AND EXISTS (
                  SELECT 1 FROM ReservationSeat rs
                  WHERE rs.reservation = r
                    AND rs.screening.id = :screeningId
                    AND rs.releaseKey = 0
                    AND rs.rowNum * 100 + rs.colNum IN :seatKeys
              )
            """)
    List<Long> findExpiredHoldsBlocking(@Param("screeningId") Long screeningId,
                                        @Param("seatKeys") List<Integer> seatKeys,
                                        @Param("pending") ReservationStatus pending,
                                        @Param("now") LocalDateTime now);

    // 조건이 있어야 값이 실제로 바뀔 때만 행에 맞는다. 다른 요청이 먼저 만료시킨 행을 같은 값으로 다시 쓰면 InnoDB는
    // 새 버전을 만들지 않아, 이 트랜잭션의 스냅샷에 옛 행이 남는다. 예매 행만 새 버전이 되고 좌석 행은 옛 버전으로 보이면
    // 사전 점유 검사가 빈 좌석을 점유로 판정한다. 벌크 UPDATE는 감사 리스너를 거치지 않아 updatedAt을 직접 쓴다.
    @Modifying
    @Query("""
            UPDATE Reservation r SET r.status = :expired, r.updatedAt = :now
            WHERE r.id = :id AND r.status = :pending AND r.expiresAt <= :now
            """)
    int expireIfPending(@Param("id") Long id,
                        @Param("pending") ReservationStatus pending,
                        @Param("expired") ReservationStatus expired,
                        @Param("now") LocalDateTime now);
}
