package com.ceos24.cgv.domain.user.repository;

import com.ceos24.cgv.domain.user.entity.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 같은 토큰의 재발급을 한 줄로 세운다. 잠금 읽기는 스냅샷이 아니라 최신 커밋본을 읽어서,
    // 뒤 요청은 앞 요청이 남긴 사용 완료 표시를 본다.
    // user를 조인하지 않는다. MySQL의 FOR UPDATE는 조인된 users 행까지 잠가 같은 사용자의 다른 기기 재발급까지 줄을 서게 된다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    // 이미 폐기된 행은 건드리지 않아 처음 폐기 시각이 남는다. 벌크 UPDATE는 감사 리스너를 거치지 않아 updatedAt을 직접 쓴다.
    // 영속성 컨텍스트를 우회하므로, 같은 트랜잭션에서 읽어 둔 엔티티가 폐기 전 상태로 남지 않게 비운다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE RefreshToken rt SET rt.revokedAt = :now, rt.updatedAt = :now
            WHERE rt.familyId = :familyId AND rt.revokedAt IS NULL
            """)
    int revokeFamily(@Param("familyId") String familyId, @Param("now") LocalDateTime now);

    // 지울 행을 잠금 없는 읽기로 먼저 고른다. user_id 범위로 바로 DELETE하면 InnoDB가 그 범위의 간격까지 잠가,
    // 같은 사용자가 두 기기에서 동시에 로그인할 때 서로의 INSERT를 막아 교착이 날 수 있다.
    @Query("SELECT rt.id FROM RefreshToken rt WHERE rt.user.id = :userId AND rt.expiresAt <= :now")
    List<Long> findExpiredIdsByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    // 기본키로만 지워 고른 행 외에는 잠그지 않는다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken rt WHERE rt.id IN :ids")
    int deleteAllByIdIn(@Param("ids") List<Long> ids);
}
