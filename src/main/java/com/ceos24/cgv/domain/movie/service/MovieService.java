package com.ceos24.cgv.domain.movie.service;

import com.ceos24.cgv.domain.movie.dto.MovieResponse;
import com.ceos24.cgv.domain.movie.entity.Movie;
import com.ceos24.cgv.domain.movie.repository.MovieRepository;
import com.ceos24.cgv.domain.reservation.entity.ReservationStatus;
import com.ceos24.cgv.domain.reservation.repository.ReservationSeatRepository;
import com.ceos24.cgv.domain.reservation.repository.ReservationSeatRepository.MovieSeatCountProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovieService {

    private final MovieRepository movieRepository;
    private final ReservationSeatRepository reservationSeatRepository;

    // 예매율 순. 집계 배치도 비정규화 컬럼도 두지 않고 조회 시점에 GROUP BY 한 번으로 센다.
    // 동점은 최신 개봉순으로 가른다.
    public List<MovieResponse> findAll() {
        Map<Long, Long> reservedByMovie = reservationSeatRepository
                .countConfirmedByMovie(ReservationStatus.RESERVED).stream()
                .collect(Collectors.toMap(
                        MovieSeatCountProjection::getMovieId,
                        MovieSeatCountProjection::getReservedCount));

        Comparator<Movie> byPopularity = Comparator
                .comparingLong((Movie m) -> reservedByMovie.getOrDefault(m.getId(), 0L)).reversed()
                .thenComparing(Movie::getReleaseDate, Comparator.reverseOrder());

        return movieRepository.findAll().stream()
                .sorted(byPopularity)
                .map(m -> MovieResponse.from(m, reservedByMovie.getOrDefault(m.getId(), 0L)))
                .toList();
    }

    public MovieResponse findById(Long id) {
        Movie movie = movieRepository.getByIdOrThrow(id);
        return MovieResponse.from(movie,
                reservationSeatRepository.countConfirmedByMovieId(id, ReservationStatus.RESERVED));
    }
}
