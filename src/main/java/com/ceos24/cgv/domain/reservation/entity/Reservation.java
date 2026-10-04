package com.ceos24.cgv.domain.reservation.entity;

import com.ceos24.cgv.global.entity.BaseTimeEntity;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.domain.screening.entity.Screening;
import com.ceos24.cgv.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation extends BaseTimeEntity {

    private static final int HOLD_MINUTES = 10;
    private static final int CANCEL_DEADLINE_MINUTES = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "screening_id", nullable = false)
    private Screening screening;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    // 선점 만료 예정 시각. 확정 후에는 의미가 없지만, 조회 조건을 단순하게 두려고 비우지 않는다.
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime confirmedAt;

    private LocalDateTime cancelledAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ReservationSeat> seats = new ArrayList<>();

    // 좌석을 고른 시각은 createdAt이 갖는다.
    @Builder
    private Reservation(User user, Screening screening, LocalDateTime now) {
        this.user = user;
        this.screening = screening;
        this.status = ReservationStatus.PENDING;
        this.expiresAt = now.plusMinutes(HOLD_MINUTES);
    }

    // screening을 부모 값에서 가져와 부모-자식 불일치를 구조적으로 차단한다.
    public void addSeat(int rowNum, int colNum, AudienceType audienceType, int basePrice) {
        ReservationSeat seat = ReservationSeat.builder()
                .reservation(this)
                .screening(this.screening)
                .rowNum(rowNum)
                .colNum(colNum)
                .audienceType(audienceType)
                .paidPrice(audienceType.calculatePrice(basePrice))
                .build();
        this.seats.add(seat);
    }

    public boolean isExpired(LocalDateTime now) {
        return this.status == ReservationStatus.PENDING && !now.isBefore(this.expiresAt);
    }

    public void confirm(LocalDateTime now) {
        requirePending();
        if (isExpired(now)) {
            throw new CustomException(ErrorCode.RESERVATION_EXPIRED);
        }
        this.status = ReservationStatus.RESERVED;
        this.confirmedAt = now;
    }

    public void cancel(LocalDateTime now) {
        if (this.status == ReservationStatus.CANCELLED) {
            throw new CustomException(ErrorCode.ALREADY_CANCELLED);
        }
        if (this.status == ReservationStatus.EXPIRED) {
            throw new CustomException(ErrorCode.RESERVATION_EXPIRED);
        }
        // 취소 기한은 확정된 예매에만 적용한다. 선점은 아직 확정 전이라 언제든 놓을 수 있다.
        if (this.status == ReservationStatus.RESERVED && !isCancellableAt(now)) {
            throw new CustomException(ErrorCode.CANCEL_DEADLINE_PASSED);
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = now;
        releaseSeats();
    }

    public int getTotalPrice() {
        return seats.stream()
                .mapToInt(ReservationSeat::getPaidPrice)
                .sum();
    }

    private boolean isCancellableAt(LocalDateTime now) {
        return now.isBefore(screening.getStartAt().minusMinutes(CANCEL_DEADLINE_MINUTES));
    }

    // 좌석 행은 남기고 점유만 푼다. 유니크 제약에서 빠지므로 같은 좌석을 다시 팔 수 있다.
    private void releaseSeats() {
        this.seats.forEach(seat -> seat.release(this.id));
    }

    private void requirePending() {
        if (this.status != ReservationStatus.PENDING) {
            throw new CustomException(ErrorCode.RESERVATION_NOT_PENDING);
        }
    }
}
