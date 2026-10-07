package com.ceos24.cgv.domain.reservation.entity;

import com.ceos24.cgv.global.entity.BaseTimeEntity;
import com.ceos24.cgv.domain.screening.entity.Screening;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_seat_screening_row_col_release",
        columnNames = {"screening_id", "row_num", "col_num", "release_key"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationSeat extends BaseTimeEntity {

    private static final long OCCUPIED = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_seat_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "screening_id", nullable = false)
    private Screening screening;

    @Column(name = "row_num", nullable = false)
    private int rowNum;

    @Column(name = "col_num", nullable = false)
    private int colNum;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience_type", nullable = false, length = 20)
    private AudienceType audienceType;

    // 예매 시점 가격. 회차 가격이 바뀌어도 과거 결제 금액은 유지되어야 한다.
    @Column(nullable = false)
    private int paidPrice;

    // 점유 중이면 0, 풀린 좌석이면 자기 예매 id가 들어간다.
    // MySQL에 partial unique index가 없어 "점유 중인 행만 유일"을 이렇게 표현한다.
    // 행을 지우지 않으므로 어느 좌석을 얼마에 취소했는지가 남는다.
    @Column(name = "release_key", nullable = false)
    private long releaseKey;

    @Builder
    private ReservationSeat(Reservation reservation, Screening screening, int rowNum, int colNum,
                            AudienceType audienceType, int paidPrice) {
        this.reservation = reservation;
        this.screening = screening;
        this.rowNum = rowNum;
        this.colNum = colNum;
        this.audienceType = audienceType;
        this.paidPrice = paidPrice;
        this.releaseKey = OCCUPIED;
    }

    // 해제 값으로 예매 id를 쓰면 한 예매가 같은 좌석을 두 번 가질 수 없어
    // 풀린 행끼리 절대 충돌하지 않는다. 시각을 쓰면 동시 해제 시 충돌할 수 있다.
    void release(Long reservationId) {
        this.releaseKey = reservationId;
    }

    public boolean isOccupied() {
        return this.releaseKey == OCCUPIED;
    }

    public String getLabel() {
        return label(rowNum, colNum);
    }

    // 좌석 라벨 규칙을 한 곳에 둔다. 회차 좌석 조회도 좌표만 갖고 같은 규칙을 쓴다.
    public static String label(int rowNum, int colNum) {
        return String.valueOf((char) ('A' + rowNum - 1)) + colNum;
    }

    // (행, 열) 쌍을 IN 절에 넣을 방법이 DB마다 달라 스칼라 하나로 접는다. 열 수는 상영관 종류 최대가 22라
    // 100진 자리에서 겹치지 않는다. ReservationRepository.findExpiredHoldsBlocking의 JPQL도 같은 식을 쓴다.
    public static int key(int rowNum, int colNum) {
        return rowNum * 100 + colNum;
    }
}
