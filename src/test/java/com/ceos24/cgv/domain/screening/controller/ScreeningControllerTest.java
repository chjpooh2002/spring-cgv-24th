package com.ceos24.cgv.domain.screening.controller;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.branch.entity.Theater;
import com.ceos24.cgv.domain.branch.entity.TheaterType;
import com.ceos24.cgv.domain.movie.entity.Movie;
import com.ceos24.cgv.domain.reservation.entity.AudienceType;
import com.ceos24.cgv.domain.reservation.entity.Reservation;
import com.ceos24.cgv.domain.screening.entity.Screening;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.support.ControllerIntegrationTest;
import com.ceos24.cgv.support.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScreeningControllerTest extends ControllerIntegrationTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 1, 9, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    // 조회 범위가 현재 시각에 걸려 있어 시간을 고정해야 한다
    @MockitoBean Clock clock;

    private Branch gangnam;
    private Branch hongdae;
    private Theater standardHall;
    private Theater imaxHall;
    private Movie movieA;
    private Movie movieB;

    @BeforeEach
    void setUp() {
        setNow(NOW);

        gangnam = persist(TestFixtures.branch("강남점"));
        hongdae = persist(TestFixtures.branch("홍대점"));
        standardHall = persist(TestFixtures.theater(gangnam, TheaterType.STANDARD, "1관"));
        imaxHall = persist(TestFixtures.theater(gangnam, TheaterType.IMAX, "2관"));
        movieA = persist(TestFixtures.movie("범죄도시4"));
        movieB = persist(TestFixtures.movie("파묘"));
    }

    private void setNow(LocalDateTime now) {
        given(clock.instant()).willReturn(now.atZone(ZONE).toInstant());
        given(clock.getZone()).willReturn(ZONE);
    }

    @Test
    void 회차_목록은_지점과_상영관_종류로_묶여_나온다() throws Exception {
        Theater hongdaeHall = persist(TestFixtures.theater(hongdae, TheaterType.STANDARD, "1관"));
        persist(TestFixtures.screening(imaxHall, movieA, TODAY.atTime(10, 0), 14000));
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(11, 0), 14000));
        persist(TestFixtures.screening(hongdaeHall, movieB, TODAY.atTime(12, 0), 14000));
        flushAndClear();

        mockMvc.perform(get("/api/screenings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.date").value("2024-06-01"))
                .andExpect(jsonPath("$.data.branches.length()").value(2))
                .andExpect(jsonPath("$.data.branches[0].branchName").value("강남점"))
                // TheaterType 선언 순서 — 일반관이 IMAX보다 앞
                .andExpect(jsonPath("$.data.branches[0].formats[0].theaterTypeName").value("일반관"))
                .andExpect(jsonPath("$.data.branches[0].formats[1].theaterTypeName").value("IMAX"))
                .andExpect(jsonPath("$.data.branches[0].formats[1].screeningCount").value(1))
                .andExpect(jsonPath("$.data.branches[1].branchName").value("홍대점"));
    }

    @Test
    void 회차_카드에_잔여석과_총석이_실린다() throws Exception {
        Screening screening = persist(
                TestFixtures.screening(standardHall, movieA, TODAY.atTime(11, 0), 14000));
        flushAndClear();

        mockMvc.perform(get("/api/screenings"))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].id")
                        .value(screening.getId()))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].theaterName")
                        .value("1관"))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].movieTitle")
                        .value("범죄도시4"))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].totalSeats").value(80))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].remainingSeats").value(80))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].soldOut").value(false));
    }

    @Test
    void 선점_중인_좌석은_잔여석에서_빠지고_만료되면_돌아온다() throws Exception {
        Screening screening = persist(
                TestFixtures.screening(standardHall, movieA, TODAY.atTime(20, 0), 14000));
        User user = persist(TestFixtures.user("testuser01"));
        Reservation hold = TestFixtures.hold(user, screening, NOW);
        hold.addSeat(1, 1, AudienceType.ADULT);
        hold.addSeat(1, 2, AudienceType.ADULT);
        persist(hold);
        flushAndClear();

        mockMvc.perform(get("/api/screenings"))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].remainingSeats").value(78));

        setNow(NOW.plusMinutes(11));
        mockMvc.perform(get("/api/screenings"))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].remainingSeats").value(80));
    }

    @Test
    void 영화_필터() throws Exception {
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(10, 0), 14000));
        persist(TestFixtures.screening(standardHall, movieB, TODAY.atTime(14, 0), 14000));
        flushAndClear();

        mockMvc.perform(get("/api/screenings").param("movieId", String.valueOf(movieB.getId())))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings.length()").value(1))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].movieTitle").value("파묘"));
    }

    @Test
    void 극장_복수_선택_필터() throws Exception {
        Theater hongdaeHall = persist(TestFixtures.theater(hongdae, TheaterType.STANDARD, "1관"));
        Branch busan = persist(TestFixtures.branch("서면점"));
        Theater busanHall = persist(TestFixtures.theater(busan, TheaterType.STANDARD, "1관"));
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(10, 0), 14000));
        persist(TestFixtures.screening(hongdaeHall, movieA, TODAY.atTime(11, 0), 14000));
        persist(TestFixtures.screening(busanHall, movieA, TODAY.atTime(12, 0), 14000));
        flushAndClear();

        mockMvc.perform(get("/api/screenings")
                        .param("branchIds", gangnam.getId() + "," + hongdae.getId()))
                .andExpect(jsonPath("$.data.branches.length()").value(2))
                .andExpect(jsonPath("$.data.branches[0].branchName").value("강남점"))
                .andExpect(jsonPath("$.data.branches[1].branchName").value("홍대점"));
    }

    @Test
    void 상영관_종류_필터() throws Exception {
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(10, 0), 14000));
        persist(TestFixtures.screening(imaxHall, movieA, TODAY.atTime(11, 0), 14000));
        flushAndClear();

        mockMvc.perform(get("/api/screenings").param("theaterType", "IMAX"))
                .andExpect(jsonPath("$.data.branches[0].formats.length()").value(1))
                .andExpect(jsonPath("$.data.branches[0].formats[0].theaterTypeName").value("IMAX"));
    }

    @Test
    void 시간대_필터() throws Exception {
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(11, 0), 14000));
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(19, 0), 14000));
        flushAndClear();

        mockMvc.perform(get("/api/screenings").param("timeSlot", "EVENING"))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings.length()").value(1))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].startAt")
                        .value("2024-06-01T19:00:00"));
    }

    @Test
    void 이미_시작한_회차는_빠진다() throws Exception {
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(8, 0), 14000));
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(10, 0), 14000));
        flushAndClear();

        mockMvc.perform(get("/api/screenings"))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings.length()").value(1))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings[0].startAt")
                        .value("2024-06-01T10:00:00"));
    }

    @Test
    void 다른_날짜를_고르면_그날_회차만_나온다() throws Exception {
        persist(TestFixtures.screening(standardHall, movieA, TODAY.atTime(10, 0), 14000));
        persist(TestFixtures.screening(standardHall, movieA, TODAY.plusDays(1).atTime(10, 0), 14000));
        flushAndClear();

        mockMvc.perform(get("/api/screenings").param("date", "2024-06-02"))
                .andExpect(jsonPath("$.data.date").value("2024-06-02"))
                .andExpect(jsonPath("$.data.branches[0].formats[0].screenings.length()").value(1));
    }

    @Test
    void 없는_시간대값이면_400() throws Exception {
        mockMvc.perform(get("/api/screenings").param("timeSlot", "NOWHERE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_VALUE"));
    }

    // ─── 좌석 조회 ────────────────────────────────────────────────────────────

    @Test
    void 회차_좌석_조회() throws Exception {
        Screening screening = persist(
                TestFixtures.screening(standardHall, movieA, TODAY.atTime(10, 0), 14000));
        User user = persist(TestFixtures.user("testuser01"));

        Reservation reservation = TestFixtures.hold(user, screening, NOW);
        reservation.addSeat(1, 7, AudienceType.ADULT);
        reservation.addSeat(2, 3, AudienceType.ADULT);
        persist(reservation);
        flushAndClear();

        mockMvc.perform(get("/api/screenings/{id}/seats", screening.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.screeningId").value(screening.getId()))
                .andExpect(jsonPath("$.data.rowCount").value(8))
                .andExpect(jsonPath("$.data.colCount").value(10))
                .andExpect(jsonPath("$.data.reservedSeats.length()").value(2))
                .andExpect(jsonPath("$.data.reservedSeats[0]").value("A7"))
                .andExpect(jsonPath("$.data.reservedSeats[1]").value("B3"));
    }

    @Test
    void 없는_회차_좌석_조회_404() throws Exception {
        mockMvc.perform(get("/api/screenings/9999/seats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SCREENING_NOT_FOUND"));
    }
}
