package com.ceos24.cgv.domain.reservation.entity;

import java.time.LocalDateTime;

// 좌석이 풀리는 경로가 사용자 취소와 시간 만료로 나뉜다.
// 둘을 CANCELLED 하나로 합치면 나중에 "이 좌석이 왜 풀렸나"를 되짚을 수 없다.
public enum ReservationStatus {

    PENDING("결제대기"),
    RESERVED("예매완료"),
    CANCELLED("취소"),
    EXPIRED("선점만료");

    private final String displayName;

    ReservationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }

    // 만료 규칙을 엔티티와 조회 프로젝션이 함께 쓰도록 상태 쪽에 둔다.
    // 프로젝션 경로에는 엔티티가 없어, 엔티티에만 두면 같은 규칙을 DTO에 한 번 더 써야 했다.
    public boolean isHoldExpired(LocalDateTime expiresAt, LocalDateTime now) {
        return this == PENDING && !now.isBefore(expiresAt);
    }

    // 만료된 선점은 DB 상태가 아직 PENDING이어도 이미 좌석을 놓은 것이나 마찬가지다.
    // 정리는 다음 좌석 선점 요청이 하고, 조회는 현재 사실만 보여준다.
    public ReservationStatus resolve(LocalDateTime expiresAt, LocalDateTime now) {
        return isHoldExpired(expiresAt, now) ? EXPIRED : this;
    }
}
