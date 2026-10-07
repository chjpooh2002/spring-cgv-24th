package com.ceos24.cgv.domain.screening.service;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.branch.entity.TheaterType;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.domain.reservation.entity.ReservationSeat;
import com.ceos24.cgv.domain.reservation.entity.ReservationStatus;
import com.ceos24.cgv.domain.reservation.repository.ReservationSeatRepository;
import com.ceos24.cgv.domain.reservation.repository.ReservationSeatRepository.SeatCountProjection;
import com.ceos24.cgv.domain.screening.dto.ScreeningListResponse;
import com.ceos24.cgv.domain.screening.dto.ScreeningListResponse.BranchGroup;
import com.ceos24.cgv.domain.screening.dto.ScreeningListResponse.FormatGroup;
import com.ceos24.cgv.domain.screening.dto.ScreeningResponse;
import com.ceos24.cgv.domain.screening.dto.ScreeningSeatsResponse;
import com.ceos24.cgv.domain.screening.entity.Screening;
import com.ceos24.cgv.domain.screening.entity.TimeSlot;
import com.ceos24.cgv.domain.screening.repository.ScreeningRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScreeningService {

    // 극장을 고르지 않았을 때 IN 절에 넘길 자리 채우기. JPQL은 빈 컬렉션 바인딩을 허용하지
    // 않으므로 조건 자체는 플래그로 끄고 파라미터만 채운다.
    private static final List<Long> NO_BRANCH_FILTER = List.of(0L);

    private final ScreeningRepository screeningRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final Clock clock;

    public ScreeningListResponse search(Long movieId, List<Long> branchIds, LocalDate date,
                                        TheaterType theaterType, TimeSlot timeSlot) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate targetDate = date != null ? date : now.toLocalDate();
        SearchWindow window = searchWindow(targetDate, timeSlot, now);
        if (window.isEmpty()) {
            return new ScreeningListResponse(targetDate, List.of());
        }

        boolean filterByBranch = branchIds != null && !branchIds.isEmpty();
        List<Screening> screenings = screeningRepository.search(
                movieId,
                filterByBranch,
                filterByBranch ? branchIds : NO_BRANCH_FILTER,
                theaterType,
                window.startInclusive(),
                window.endExclusive());

        return new ScreeningListResponse(targetDate, groupByBranch(screenings, now));
    }

    public ScreeningSeatsResponse getSeats(Long screeningId) {
        Screening screening = screeningRepository.findByIdWithTheater(screeningId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCREENING_NOT_FOUND));

        // 결제 전 선점도 남이 고를 수 없으므로 예매된 좌석과 똑같이 막힌 것으로 내려준다.
        List<String> labels = reservationSeatRepository
                .findOccupiedPositionsByScreeningId(screeningId, ReservationStatus.PENDING, LocalDateTime.now(clock))
                .stream()
                .map(p -> ReservationSeat.label(p.getRowNum(), p.getColNum()))
                .toList();

        return ScreeningSeatsResponse.from(screening, labels);
    }

    // 시간대를 고르지 않으면 그날 하루 전체다. 이미 시작한 회차는 예매할 수 없으므로 시작을 지금으로 당긴다.
    // 지난 날짜를 조회하면 시작이 끝보다 뒤가 되어 빈 범위가 된다.
    private SearchWindow searchWindow(LocalDate targetDate, TimeSlot timeSlot, LocalDateTime now) {
        LocalDateTime slotStart = timeSlot != null ? timeSlot.startOn(targetDate) : targetDate.atStartOfDay();
        LocalDateTime endExclusive = timeSlot != null ? timeSlot.endOn(targetDate) : targetDate.plusDays(1).atStartOfDay();
        LocalDateTime startInclusive = slotStart.isAfter(now) ? slotStart : now;
        return new SearchWindow(startInclusive, endExclusive);
    }

    private record SearchWindow(LocalDateTime startInclusive, LocalDateTime endExclusive) {

        boolean isEmpty() {
            return !startInclusive.isBefore(endExclusive);
        }
    }

    // 쿼리가 지점 이름·시작 시각 순으로 정렬해 두므로 순서를 보존하며 묶기만 한다.
    private List<BranchGroup> groupByBranch(List<Screening> screenings, LocalDateTime now) {
        if (screenings.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> occupied = occupiedSeatCounts(screenings, now);

        Map<Long, List<Screening>> byBranchId = screenings.stream()
                .collect(Collectors.groupingBy(s -> s.getTheater().getBranch().getId(),
                        LinkedHashMap::new, Collectors.toList()));

        return byBranchId.values().stream()
                .map(inBranch -> {
                    Branch branch = inBranch.getFirst().getTheater().getBranch();
                    return new BranchGroup(branch.getId(), branch.getName(), groupByTheaterType(inBranch, occupied));
                })
                .toList();
    }

    // 상영관 종류는 STRING으로 저장돼 DB가 정렬하면 알파벳순이 된다.
    // TreeMap의 자연 순서(enum 선언 순서)로 다시 잡는다.
    private List<FormatGroup> groupByTheaterType(List<Screening> screenings, Map<Long, Long> occupied) {
        Map<TheaterType, List<ScreeningResponse>> byType = screenings.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getTheater().getTheaterType(),
                        TreeMap::new,
                        Collectors.mapping(
                                s -> ScreeningResponse.from(
                                        s, occupied.getOrDefault(s.getId(), 0L).intValue()),
                                Collectors.toList())));

        return byType.entrySet().stream()
                .map(entry -> FormatGroup.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    private Map<Long, Long> occupiedSeatCounts(List<Screening> screenings, LocalDateTime now) {
        List<Long> ids = screenings.stream().map(Screening::getId).toList();
        return reservationSeatRepository
                .countOccupiedByScreeningIds(ids, ReservationStatus.PENDING, now).stream()
                .collect(Collectors.toMap(
                        SeatCountProjection::getScreeningId,
                        SeatCountProjection::getReservedCount));
    }
}
