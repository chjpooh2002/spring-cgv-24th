package com.ceos24.cgv.domain.user.repository;

import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// 조회 실패를 어떤 오류로 알릴지를 여기 한 곳에 둔다. 다른 도메인 서비스도 사용자를 찾으므로
// 서비스에 두면 도메인마다 같은 orElseThrow가 반복된다(다른 도메인의 서비스는 참조하지 않는다).
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByLoginId(String loginId);

    Optional<User> findByLoginId(String loginId);

    default User getByIdOrThrow(Long id) {
        return findById(id).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    default void validateExists(Long id) {
        if (!existsById(id)) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
    }
}
