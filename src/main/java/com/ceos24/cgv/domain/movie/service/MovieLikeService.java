package com.ceos24.cgv.domain.movie.service;

import com.ceos24.cgv.domain.movie.dto.MovieLikeResponse;
import com.ceos24.cgv.domain.movie.entity.Movie;
import com.ceos24.cgv.domain.movie.entity.MovieLike;
import com.ceos24.cgv.domain.movie.repository.MovieLikeRepository;
import com.ceos24.cgv.domain.movie.repository.MovieRepository;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.domain.user.repository.UserRepository;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MovieLikeService {

    private final MovieLikeRepository movieLikeRepository;
    private final MovieRepository movieRepository;
    private final UserRepository userRepository;

    // 이미 찜한 상태면 요청의 목표가 성립해 있으므로 성공으로 끝낸다.
    // 존재 검증을 INSERT 앞에 두는 이유: FK 위반도 DataIntegrityViolationException이라
    // 검증이 없으면 없는 영화가 경합 충돌로 잘못 번역된다.
    @Transactional
    public void like(Long movieId, Long userId) {
        User user = userRepository.getByIdOrThrow(userId);
        Movie movie = movieRepository.getByIdOrThrow(movieId);

        if (movieLikeRepository.existsByUserIdAndMovieId(userId, movieId)) {
            return;
        }

        try {
            movieLikeRepository.saveAndFlush(MovieLike.builder().user(user).movie(movie).build());
        } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
            // 동시 요청이 먼저 INSERT한 경우다. 결과 상태는 "찜함"이지만 flush 실패로 트랜잭션이
            // 이미 rollback-only라 성공을 돌려줄 수 없다. 재시도하면 위 pre-check에서 성공한다.
            throw new CustomException(ErrorCode.LIKE_REQUEST_CONFLICT);
        }
    }

    // 대상 존재를 검증하지 않는다. 없는 영화면 찜도 없으므로 "찜 아님"이 이미 성립한다.
    @Transactional
    public void unlike(Long movieId, Long userId) {
        movieLikeRepository.deleteByUserIdAndMovieId(userId, movieId);
    }

    // 없는 사용자를 빈 목록으로 돌려주면 잘못된 id가 "찜 없음"으로 숨는다.
    public List<MovieLikeResponse> findMyLikes(Long userId) {
        userRepository.validateExists(userId);
        return movieLikeRepository.findAllByUserIdWithMovie(userId).stream()
                .map(MovieLikeResponse::from)
                .toList();
    }
}
