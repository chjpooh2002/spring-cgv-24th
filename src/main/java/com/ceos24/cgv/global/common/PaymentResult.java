package com.ceos24.cgv.global.common;

// 실제 결제는 mock이다. 결제수단·PG 연동 없이 완료/실패 전이만 다룬다.
// 예매와 매점이 함께 쓰므로 어느 한 도메인의 요청 형식에 묶이지 않게 따로 둔다.
public enum PaymentResult {
    SUCCESS, FAILURE
}
