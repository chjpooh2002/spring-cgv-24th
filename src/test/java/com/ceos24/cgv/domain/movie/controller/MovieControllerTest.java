package com.ceos24.cgv.domain.movie.controller;

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
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MovieControllerTest extends ControllerIntegrationTest {

    @Test
    void 영화_목록_조회() throws Exception {
        persist(TestFixtures.movie("범죄도시4"));
        persist(TestFixtures.movie("파묘"));

        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void 영화_단건_조회() throws Exception {
        Movie movie = persist(TestFixtures.movie("범죄도시4"));

        mockMvc.perform(get("/api/movies/{id}", movie.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(movie.getId()))
                .andExpect(jsonPath("$.data.title").value("범죄도시4"))
                .andExpect(jsonPath("$.data.director").value("감독"));
    }

    @Test
    void 확정_예매가_많은_영화가_먼저_나온다() throws Exception {
        Branch branch = persist(TestFixtures.branch("강남점"));
        Theater theater = persist(TestFixtures.theater(branch, TheaterType.STANDARD, "1관"));
        User user = persist(TestFixtures.user("testuser01"));

        Movie quiet = persist(TestFixtures.movie("한산한영화", LocalDate.of(2024, 1, 1)));
        Movie popular = persist(TestFixtures.movie("흥행작", LocalDate.of(2024, 2, 1)));
        LocalDateTime start = LocalDateTime.now().plusDays(1);
        Screening quietScreening = persist(TestFixtures.screening(theater, quiet, start, 14000));
        Screening popularScreening = persist(
                TestFixtures.screening(theater, popular, start.plusHours(3), 14000));

        confirm(user, popularScreening, 1, 1, 1, 2);   // 확정 2석
        confirm(user, quietScreening, 1, 1);           // 확정 1석
        hold(user, popularScreening, 2, 1);            // 선점은 세지 않는다
        flushAndClear();

        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("흥행작"))
                .andExpect(jsonPath("$.data[0].reservedSeatCount").value(2))
                .andExpect(jsonPath("$.data[1].title").value("한산한영화"))
                .andExpect(jsonPath("$.data[1].reservedSeatCount").value(1));
    }

    @Test
    void 예매가_없으면_최신_개봉순으로_가른다() throws Exception {
        persist(TestFixtures.movie("옛날영화", LocalDate.of(2023, 1, 1)));
        persist(TestFixtures.movie("신작", LocalDate.of(2024, 5, 1)));
        flushAndClear();

        mockMvc.perform(get("/api/movies"))
                .andExpect(jsonPath("$.data[0].title").value("신작"))
                .andExpect(jsonPath("$.data[1].title").value("옛날영화"));
    }

    @Test
    void 없는_영화_조회_404() throws Exception {
        mockMvc.perform(get("/api/movies/9999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_FOUND"));
    }

    private void confirm(User user, Screening screening, int... rowCols) {
        Reservation reservation = hold(user, screening, rowCols);
        reservation.confirm(LocalDateTime.now());
    }

    private Reservation hold(User user, Screening screening, int... rowCols) {
        Reservation reservation = TestFixtures.hold(user, screening, LocalDateTime.now());
        for (int i = 0; i < rowCols.length; i += 2) {
            reservation.addSeat(rowCols[i], rowCols[i + 1], AudienceType.ADULT);
        }
        return persist(reservation);
    }
}
