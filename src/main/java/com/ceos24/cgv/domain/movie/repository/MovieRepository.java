package com.ceos24.cgv.domain.movie.repository;

import com.ceos24.cgv.domain.movie.entity.Movie;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    default Movie getByIdOrThrow(Long id) {
        return findById(id).orElseThrow(() -> new CustomException(ErrorCode.MOVIE_NOT_FOUND));
    }
}
