package com.ceos24.cgv.domain.user.dto;

/**
 * 로그인과 재발급이 같은 모양으로 응답한다. 둘 다 "새 토큰 한 쌍"을 내주는 일이라 따로 두면
 * 한쪽에 필드를 더할 때 다른 쪽을 빠뜨리기 쉽다.
 *
 * @param expiresIn             액세스 토큰 유효 시간(초). 클라이언트가 만료 전에 재발급을 준비할 수 있게 준다.
 * @param refreshToken          다음 재발급·로그아웃 요청 본문에 넣을 원문. 서버는 해시만 가지고 있어 다시 보여줄 수 없다.
 *                              재발급 응답이면 요청에 보낸 토큰은 사용 완료되어 다시 쓸 수 없다.
 * @param refreshTokenExpiresIn 리프레시 토큰의 남은 유효 시간(초). 무작위 문자열이라 토큰 자체에서는 만료를 읽을 수 없다.
 *                              만료 시각은 로그인 때 정해져 재발급해도 늘어나지 않으므로, 로그인 직후에만 전체 유효기간과 같다.
 */
public record TokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        long refreshTokenExpiresIn
) {
    private static final String BEARER = "Bearer";

    public static TokenResponse of(String accessToken, long expiresIn,
                                   String refreshToken, long refreshTokenExpiresIn) {
        return new TokenResponse(accessToken, BEARER, expiresIn, refreshToken, refreshTokenExpiresIn);
    }
}
