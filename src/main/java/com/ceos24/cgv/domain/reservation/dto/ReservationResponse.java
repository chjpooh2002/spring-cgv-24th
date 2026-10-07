package com.ceos24.cgv.domain.reservation.dto;

import com.ceos24.cgv.domain.reservation.entity.AudienceType;
import com.ceos24.cgv.domain.reservation.entity.Reservation;
import com.ceos24.cgv.domain.reservation.entity.ReservationSeat;
import com.ceos24.cgv.domain.reservation.entity.ReservationStatus;
import com.ceos24.cgv.domain.screening.entity.Screening;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReservationResponse(
        Long id,
        Long userId,
        ScreeningSummary screening,
        ReservationStatus status,
        String statusName,
        List<SeatSummary> seats,
        int totalPrice,
        LocalDateTime selectedAt,
        LocalDateTime expiresAt,
        LocalDateTime confirmedAt,
        LocalDateTime cancelledAt
) {
    public record ScreeningSummary(
            Long id,
            String movieTitle,
            String theaterName,
            String branchName,
            LocalDateTime startAt,
            LocalDateTime endAt
    ) {
        public static ScreeningSummary from(Screening s) {
            return new ScreeningSummary(
                    s.getId(),
                    s.getMovie().getTitle(),
                    s.getTheater().getName(),
                    s.getTheater().getBranch().getName(),
                    s.getStartAt(),
                    s.getEndAt()
            );
        }
    }

    public record SeatSummary(
            String label,
            AudienceType audienceType,
            String audienceTypeName,
            int paidPrice
    ) {
        public static SeatSummary from(ReservationSeat seat) {
            return new SeatSummary(
                    seat.getLabel(),
                    seat.getAudienceType(),
                    seat.getAudienceType().getDisplayName(),
                    seat.getPaidPrice()
            );
        }

        public static SeatSummary from(ReservationDetailRow row) {
            return new SeatSummary(
                    ReservationSeat.label(row.rowNum(), row.colNum()),
                    row.audienceType(),
                    row.audienceType().getDisplayName(),
                    row.paidPrice()
            );
        }
    }

    public static ReservationResponse from(Reservation r, LocalDateTime now) {
        List<SeatSummary> seats = r.getSeats().stream()
                .sorted(Comparator.comparingInt(ReservationSeat::getRowNum)
                        .thenComparingInt(ReservationSeat::getColNum))
                .map(SeatSummary::from)
                .toList();

        ReservationStatus status = r.getStatus().resolve(r.getExpiresAt(), now);

        return new ReservationResponse(
                r.getId(),
                r.getUser().getId(),
                ScreeningSummary.from(r.getScreening()),
                status,
                status.getDisplayName(),
                seats,
                r.getTotalPrice(),
                r.getCreatedAt(),
                r.getExpiresAt(),
                r.getConfirmedAt(),
                r.getCancelledAt()
        );
    }

    // 조회 경로는 엔티티 없이 스칼라 행만 받는다. 헤더 값은 행마다 같으므로 첫 행에서 읽고,
    // 좌석 정렬은 쿼리의 ORDER BY가 이미 끝냈다.
    public static ReservationResponse of(List<ReservationDetailRow> rows, LocalDateTime now) {
        ReservationDetailRow head = rows.getFirst();

        List<SeatSummary> seats = rows.stream()
                .map(SeatSummary::from)
                .toList();
        int totalPrice = rows.stream()
                .mapToInt(ReservationDetailRow::paidPrice)
                .sum();

        ReservationStatus status = head.status().resolve(head.expiresAt(), now);

        return new ReservationResponse(
                head.reservationId(),
                head.userId(),
                new ScreeningSummary(
                        head.screeningId(),
                        head.movieTitle(),
                        head.theaterName(),
                        head.branchName(),
                        head.startAt(),
                        head.endAt()
                ),
                status,
                status.getDisplayName(),
                seats,
                totalPrice,
                head.selectedAt(),
                head.expiresAt(),
                head.confirmedAt(),
                head.cancelledAt()
        );
    }

    // 행은 예매 id 내림차순으로 온다. LinkedHashMap이라 묶은 뒤에도 그 순서가 유지된다.
    public static List<ReservationResponse> listOf(List<ReservationDetailRow> rows, LocalDateTime now) {
        Map<Long, List<ReservationDetailRow>> byReservation = new LinkedHashMap<>();
        for (ReservationDetailRow row : rows) {
            byReservation.computeIfAbsent(row.reservationId(), id -> new ArrayList<>()).add(row);
        }
        return byReservation.values().stream()
                .map(group -> of(group, now))
                .toList();
    }
}
