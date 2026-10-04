# 리팩토링 로그

CGV 실서비스 흐름에 맞춰 세션 단위로 리팩토링한 기록.

---

## 세션 0: 현황

코드 변경 없음. 현재 상태를 정리하고 이후 세션의 영향 범위를 확정하기 위한 세션.

### 1. 엔티티와 관계

엔티티 13개, enum 4개. 모든 엔티티는 `BaseTimeEntity`(createdAt/updatedAt)를 상속한다.

```
users ─┬─< reservation ──< reservation_seat >── screening
       ├─< purchase ──< purchase_product >── product
       ├─< movie_like >── movie
       └─< branch_like >── branch

branch ─┬─< theater ──< screening >── movie
        ├─< stock >── product
        ├─< purchase
        └─< branch_like

screening ──< reservation_seat     (좌석 중복 방지용 직접 FK)
```

| 부모 | 자식 | 방향 | 비고 |
|---|---|---|---|
| users | reservation, purchase, movie_like, branch_like | 단방향 | 모두 LAZY |
| branch | theater, stock, purchase, branch_like | 단방향 | |
| theater | screening | 단방향 | 좌석 배치는 `theater_type` enum이 보유 |
| movie | screening, movie_like | 단방향 | |
| screening | reservation | 단방향 | |
| screening | reservation_seat | 단방향 | 좌석 중복 방지를 위한 직접 FK |
| reservation | reservation_seat | **양방향** | cascade ALL + orphanRemoval (헤더-디테일) |
| purchase | purchase_product | **양방향** | cascade ALL + orphanRemoval (헤더-디테일) |

enum:

| enum | 값 | 특징 |
|---|---|---|
| `Region` | SEOUL … JEJU (10개) | 지역 탭. 선언 순서가 노출 순서 |
| `BranchStatus` | OPEN / TEMPORARILY_CLOSED / CLOSED | `isReservable()` 보유 |
| `TheaterType` | STANDARD(8×10) / SPECIAL(10×20) | `getTotalSeatCount()`, `isValidSeat()` 보유 · 세션 1-1에서 정정 |
| `ReservationStatus` | RESERVED / CANCELLED | 메서드 없음 |

유니크 제약:

- `uk_seat_screening_row_col` — (screening_id, row_num, col_num) **좌석 중복 예매 방지의 핵심**
- `uk_stock_branch_product` — (branch_id, product_id)
- `uk_branch_like_user_branch` / `uk_movie_like_user_movie` — 찜 중복 방지
- `users.login_id`

좌석 테이블은 없다. 배치는 `TheaterType.rowCount/colCount`, 좌석 위치는
`ReservationSeat.rowNum/colNum`으로 표현한다. DB가 좌석 범위를 검증할 수 없으므로
`TheaterType.isValidSeat()`가 도메인에서 책임진다.

### 2. API 엔드포인트

| 메서드 | 경로 | 하는 일 |
|---|---|---|
| GET | `/api/branches` | 지점 목록. `region` 탭 또는 `keyword` 검색. `CLOSED` 제외, 특별관 라벨 집계 |
| GET | `/api/branches/{id}` | 지점 단건 + 소속 상영관. 상태로 거르지 않음(폐관도 조회됨) |
| GET | `/api/movies` | 영화 목록 |
| GET | `/api/movies/{id}` | 영화 단건 |
| GET | `/api/screenings` | 회차 목록. `movieId`/`branchId`/`date` 옵션 필터, 잔여좌석 포함 |
| GET | `/api/screenings/{id}/seats` | 상영관 크기(rowCount/colCount) + 예매된 좌석 라벨 |
| POST | `/api/reservations` | 예매 생성 (201) |
| GET | `/api/reservations/{id}` | 예매 단건 |
| DELETE | `/api/reservations/{id}` | 예매 취소 |

모든 응답은 `ApiResponse<T>`로 감싼다.

**미구현**

- 사용자 API — Spring Security 복습 후 3주차 과제하며 구현 예정. 현재는 Request DTO로 `userId`를 직접 받는다
- 찜 API — `BranchLike`/`MovieLike` 엔티티·리포지토리만 존재
- 매점 API — `store` 도메인에 entity + repository만 있고 controller/service/dto가 없다

### 3. 예매 상태 전이 (현재)

```
   (생성)
      │  Reservation 생성자에서 RESERVED 고정
      ▼
  RESERVED ──── cancel() ────▶ CANCELLED
                                   │
                                   └── 재호출 시 ALREADY_CANCELLED (409)
```

- 중간 상태가 없다. 생성 즉시 확정 예매다. 결제 개념이 없다.
- `cancel()`은 `cancelledAt`을 기록하고 `seats.clear()`로 좌석 행을 삭제한다.
  `uk_seat_screening_row_col` 때문에 행이 남으면 해당 좌석을 재판매할 수 없어서다.
- **부작용**: 좌석 행이 사라지므로 취소된 예매는 `getTotalPrice()`가 0이 되고
  `ReservationResponse.seats`가 빈 배열이 된다. 어느 좌석을 취소했는지 이력이 남지 않는다.
  → 세션 2 재검토 대상.

동시성 처리는 `ReservationService.create()`의 2단 방어로 되어 있다.

1. pre-check — `reservationSeatRepository.findByScreeningId()`로 이미 잡힌 좌석 조회 후 비교
2. 안전망 — `saveAndFlush()`의 `DataIntegrityViolationException`을 잡아
   `SEAT_ALREADY_RESERVED`로 변환. 실제 방어선은 DB 유니크 제약이다.

### 4. 패키지 구조와 계층

```
com.ceos24.cgv
├── domain
│   ├── branch      controller service repository dto entity
│   ├── movie       controller service repository dto entity
│   ├── screening   controller service repository dto entity
│   ├── reservation controller service repository dto entity
│   ├── store       repository entity          ← controller/service/dto 없음
│   └── user        repository entity          ← controller/service/dto 없음
└── global
    ├── config      JpaConfig, SwaggerConfig
    ├── entity      BaseTimeEntity
    ├── exception   CustomException, ErrorCode, GlobalExceptionHandler
    └── response    ApiResponse
```

계층별 현재 방식:

- **Controller** — 서비스 호출과 `ApiResponse` 래핑만. Swagger 어노테이션 부착
- **Service** — `@RequiredArgsConstructor`, 클래스에 `@Transactional(readOnly = true)`,
  쓰기 메서드에만 `@Transactional`
- **Repository** — Spring Data JPA. N+1 회피용 `JOIN FETCH` 쿼리와 인터페이스 프로젝션
  (`BranchTheaterType`, `SeatCountProjection`, `SeatPositionProjection`)
- **DTO** — `record` + 정적 팩토리 `from()`. 중첩 record로 요약 표현
  (`ScreeningSummary`, `TheaterSummary`, `MovieSummary`)
- **예외** — `CustomException` + `ErrorCode` enum. `global/exception` 한 곳

도메인 간 참조 규칙(entity/repository는 허용, service는 같은 도메인만)은 지켜지고 있다.

- `ScreeningService` → `ReservationSeatRepository`
- `ReservationService` → `ScreeningRepository`, `UserRepository`

테스트는 Controller 통합 테스트(`ControllerIntegrationTest` 상속)와 Service 테스트로
나뉘고, 픽스처는 `TestFixtures`에 모여 있다. 현재 branch 11개 / movie 3개 /
screening 12개 / reservation 26개.

### 5. 세션별 영향 범위

#### (1) 극장·상영관 스키마 — **완료**

`a5bbd24`, `84cc465`, `a4eba8d`에서 이미 반영되었다.

- `Branch`에 `region`, `status`, `description`, `imageUrl` 추가
- `Region`, `BranchStatus` enum 신설
- `TheaterType`을 테이블이 아닌 enum으로 확정(rowCount/colCount 보유)
- 지역 탭 필터 / 지역명·지점명 검색 API, 특별관 라벨 집계

좌석은 Seat 엔티티 없이 `TheaterType` + `ReservationSeat.rowNum/colNum`으로 표현하는
구조가 확정이므로 추가 작업 없음.

`TheaterType`의 값 구성은 세션 1-1에서 정정한다.

#### (2) ReservationStatus에 PENDING 추가 + 예매 흐름 재설계

영향받는 파일:

| 파일 | 내용 |
|---|---|
| `ReservationStatus` | PENDING 및 확정/만료 상태 추가 |
| `Reservation` | 생성 시 초기 상태, `cancel()`, 확정·만료 전이 메서드 |
| `ReservationService.create()` | 7단계 전부 재검토 + 확정/만료 경로 신설 |
| `ReservationSeatRepository` | `countGroupedByScreeningIds()`, `findPositionsByScreeningId()`에 **상태 조건이 없다.** `reservation` 조인 필요 |
| `ScreeningService` | `search()` 잔여좌석, `getSeats()` 좌석 라벨 — 세션 3의 전제 |
| `ReservationController` | 결제 확정 엔드포인트 추가 |
| `ReservationResponse` | 상태·만료시각 표현 |
| `ErrorCode` | 전이 실패 코드 추가 |
| 테스트 | `ReservationServiceTest`, `ReservationControllerTest`, `ScreeningServiceTest` 일부 |

세션 2에서 결정할 쟁점:

- **PENDING 좌석도 점유로 본다.** 실제 CGV도 결제 전 선택 단계에서 남이 그 좌석을 잡지 못한다.
  따라서 예매 가능 좌석에서 제외해야 한다.
- 그러려면 PENDING도 `reservation_seat` 행을 실제로 만들어야 한다. 그래야
  `uk_seat_screening_row_col`이 그대로 선점 잠금 역할을 한다. 별도 비관적 락은 불필요하고,
  기존 `DataIntegrityViolationException` 안전망이 계속 유효하다.
- **만료 처리가 새 숙제다.** 결제를 포기한 PENDING 행이 유니크 인덱스를 계속 점유하면
  그 좌석이 영원히 잠긴다. 선점 만료 시각을 어디에 둘지, 만료된 행을 언제 걷어낼지
  (조회·생성 시점 lazy 정리 vs 스케줄러)를 정해야 한다.
- 취소 시 `seats.clear()` 재검토. 이력 보존과 재판매 가능성을 동시에 만족시켜야 한다.

#### (3) 회차 조회 API 재설계

영향받는 파일: `ScreeningController`, `ScreeningService`, `ScreeningRepository`,
`ScreeningResponse`, `ScreeningSeatsResponse`, 관련 테스트.

현재는 단일 평면 리스트 + 옵션 필터 3개(`movieId`/`branchId`/`date`)라
"영화 → 지점 → 날짜 → 시간" 실사용 흐름과 맞지 않는다. 잔여좌석 집계 로직이
세션 2의 상태 모델에 직접 종속된다.

#### (4) 찜 기능

`BranchLike`/`MovieLike` 엔티티와 리포지토리(`existsByUserIdAnd...`)는 이미 있다.
각 도메인에 controller/service/dto를 신규 생성한다. `userId`는 현행대로 요청에서 받는다.
`ErrorCode` 추가 필요. 목록 응답(`BranchResponse`, `MovieResponse`)에 찜 여부를 넣을지 결정해야 한다.

#### (5) 매점 구매

`store` 도메인에 controller/service/dto가 전무하다. 반면 도메인 로직은 준비되어 있다.

- `Stock.decrease()` — 재고 부족 시 `OUT_OF_STOCK`
- `Purchase.addItem()` — 자식 추가와 총액 갱신을 한 메서드에서 처리
- `ErrorCode.OUT_OF_STOCK`, `PRODUCT_NOT_FOUND` 이미 존재

재고 차감 동시성 처리가 쟁점. 좌석과 달리 유니크 제약으로는 막을 수 없다.

### 6. 순서 검토 결론

**현행 유지: (1) 완료 → (2) → (3) → (4) → (5)**

- **(2)가 (3)보다 먼저여야 한다.** PENDING이 "예매 가능 좌석"의 판정 기준을 바꾸므로,
  (3)을 먼저 하면 잔여좌석 집계와 좌석 조회 쿼리를 두 번 고치게 된다.
  실사용 흐름은 회차 조회가 앞서지만, 의존 방향은 반대다.
- (4)·(5)는 다른 세션에 의존하지 않아 언제든 가능하다. 예매 본류를 끝낸 뒤에 두는 것이 맞다.
- (4)를 (5)보다 먼저 두는 것도 타당하다. 찜이 더 작고, 목록 응답에 찜 여부를 얹을지
  결정하면서 조회 응답 구조를 한 번 더 점검하게 된다.

---

## 세션 1-1: 상영관 종류 2단 구조

세션 1의 보완. 세션 2 착수 전에 상영관 스키마를 확정하기 위해 처리했다.

### 문제

상영관 종류는 **대분류(일반관/특별관) → 실제 종류(IMAX·4DX·SCREENX)** 2단 구조이고
좌석 배치는 실제 종류가 결정한다. 그런데 `TheaterType`은 `STANDARD`/`SPECIAL` 두 값뿐이라
대분류가 곧 종류가 되어 있었다. "특별관"이라는 한 덩어리가 단일 배치(10×20)를 갖는 탓에
IMAX와 4DX의 배치가 다르다는 사실을 표현할 수 없었다.

README는 지점 목록 라벨을 `SCREENX`, `4DX`로 설명하지만 실제 응답은 `["특별관"]`
하나였다.

### 결정

`TheaterCategory` enum을 새로 두고 `TheaterType`이 이를 필드로 갖는다.

| 값 | 표시명 | 대분류 | rowCount | colCount | 총 좌석 |
|---|---|---|---|---|---|
| `STANDARD` | 일반관 | GENERAL | 8 | 10 | 80 |
| `IMAX` | IMAX | SPECIAL | 12 | 22 | 264 |
| `FOUR_DX` | 4DX | SPECIAL | 10 | 16 | 160 |
| `SCREEN_X` | SCREENX | SPECIAL | 10 | 20 | 200 |

- 대분류를 boolean이 아닌 enum으로 둔 이유: "특별관"이라는 **표시명**이 필요한 자리가
  생길 수 있다. boolean은 그 이름을 담을 곳이 없다.
- `TheaterCategory`는 `TheaterType`이 결정하는 값이라 컬럼으로 저장하지 않는다.
- `STANDARD`는 8×10을 그대로 유지했다. 예매·회차 테스트의 기대값(80석, row 9는 범위 초과)이
  이 배치에 걸려 있다.
- 저장 형태는 기존과 동일(`theater.theater_type varchar(20)`). `ddl-auto`가 운영 `create` /
  테스트 `create-drop`이라 마이그레이션이 필요 없었다.

### 변경 파일

| 파일 | 내용 |
|---|---|
| `TheaterCategory.java` | 신설. GENERAL/SPECIAL + 표시명 |
| `TheaterType.java` | 값 4개로 재정의, `category` 필드와 `isSpecial()` 추가 |
| `BranchService.java` | `!= STANDARD` → `isSpecial()`, 라벨 정렬 추가 |
| `BranchControllerTest.java` | 특별관 라벨 테스트를 IMAX+4DX 조합으로 교체 |
| `README.md` | TheaterType 표, 용어 분리·라벨 집계 서술 |

### 라벨 정렬

한 지점이 특별관을 여러 종류 보유하면 `specialTypes` 라벨 순서가 `GROUP BY` 결과 순서에
좌우된다. `TheaterType` 선언 순서로 정렬해 응답을 고정했다. 테스트는 4DX를 먼저 저장한 뒤
`["IMAX", "4DX"]`가 나오는지 확인한다.

### 확인

`./gradlew test` 53개 전부 통과. `remainingSeats == 80`과 좌석 범위 초과(row 9) 케이스가
통과하므로 STANDARD 배치 8×10은 보존되었다.

---

## 세션 2: 예매 선점 상태와 결제 흐름

리뷰 피드백 "결제가 완료되지 않았을 경우 좌석 점유는 일어나지만 예약 확정은 아닌 상태가
존재할 것"을 반영한다. 세션 0에서 남겨둔 `seats.clear()` 문제도 여기서 같이 정리했다.

### 상태 전이

| from | to | 트리거 | 좌석 | 기록 |
|---|---|---|---|---|
| — | `PENDING` | 좌석 선택 | 점유 | `expiresAt` = 선택 + 10분 |
| `PENDING` | `RESERVED` | 결제 성공 | 점유 유지 | `confirmedAt` |
| `PENDING` | `CANCELLED` | 결제 실패 / 사용자 취소 | 해제 | `cancelledAt` |
| `PENDING` | `EXPIRED` | 만료 시각 경과 | 해제 | — |
| `RESERVED` | `CANCELLED` | 취소, 상영 20분 전까지 | 해제 | `cancelledAt` |

거부되는 전이

| 시도 | 응답 |
|---|---|
| 확정된 예매 재결제 | 409 `RESERVATION_NOT_PENDING` |
| 만료된 선점 결제 | 409 `RESERVATION_EXPIRED` |
| 취소된 예매 재취소 | 409 `ALREADY_CANCELLED` |
| 상영 20분 이내 확정 예매 취소 | 409 `CANCEL_DEADLINE_PASSED` |
| 점유 중인 좌석 선택 | 409 `SEAT_ALREADY_RESERVED` |

**결제 실패는 좌석을 바로 놓는다.** 실패한 자리를 붙들고 재시도하게 두면 경쟁이 심한 회차에서
좌석 회전이 막힌다. 실제 CGV도 결제에 실패하면 좌석 선택부터 다시 진행한다.

**만료는 `CANCELLED`와 분리했다.** 사용자가 놓은 것과 시간이 지나 회수한 것은 원인이 다르고,
합치면 "이 좌석이 왜 풀렸나"를 되짚을 수 없다.

### 결정 1 — 취소 이력과 중복 방지

좌석 행을 지우지 않고 유니크 키에 해제 키를 넣는다.

```
UNIQUE (screening_id, row_num, col_num, release_key)

점유 중   release_key = 0
풀린 좌석 release_key = 자기 reservation_id
```

MySQL에 partial unique index가 없어 "점유 중인 행만 유일"을 직접 표현할 수 없다. 해제 값으로
예매 id를 쓰면 한 예매가 같은 좌석을 두 번 가질 수 없으므로 풀린 행끼리 충돌하지 않는다.
시각을 쓰면 같은 좌석이 동시에 해제될 때 충돌할 수 있다.

얻은 것: DB 유니크 제약이 그대로 최종 방어선으로 남으면서 어느 좌석을 얼마에 취소했는지가
남는다. 취소된 예매를 조회하면 `seats`와 `totalPrice`가 그대로 보인다.
치른 비용: `release_key = 0`이 "점유 중"이라는 게 도메인 언어가 아니라 주석이 필요하다.

점유 테이블을 따로 두는 안은 테이블이 늘고 두 테이블 동기화가 어긋날 수 있어 택하지 않았다.

### 결정 2 — 만료는 `expires_at` + lazy 처리

- 조회는 쿼리 조건으로 거른다.
  `release_key = 0 AND (status <> PENDING OR expires_at > :now)`
  취소·만료로 풀린 행은 `release_key`에서 이미 빠지고, 남는 예외가 만료 시각은 지났지만 아직
  정리되지 않은 선점이라 시각 조건을 더한다.
- 쓰기는 좌석을 잡기 직전에 그 회차의 만료된 선점을 실제로 해제한다. 유니크 인덱스는 만료
  시각을 모르므로 행을 놓아주지 않으면 시간이 지난 좌석도 다시 잡을 수 없다.
- 정리 범위는 요청된 회차 한 건으로 좁혔다.

스케줄러를 두지 않은 이유: 정확성은 위 두 경로로 이미 보장된다. 스케줄러는 "언젠가 정리된다"는
보조 수단일 뿐인데 시간 제어·테스트 비용만 늘어난다. 나중에 얹어도 이 로직은 그대로 쓴다.

**구현 중 걸린 것**: 해제(UPDATE)를 새 좌석(INSERT)보다 먼저 flush해야 한다. 한 번에
flush하면 Hibernate가 INSERT를 UPDATE보다 앞서 내보내 같은 좌석에서 유니크 충돌이 난다.
`ReservationService.releaseExpiredHolds()`에서 명시적으로 `flush()`를 부르는 이유다.

### 결정 3 — 동시성

- 막히는 지점은 `reservation_seat` INSERT 시 유니크 인덱스다.
- `saveAndFlush()`로 INSERT를 즉시 강제하고 `DataIntegrityViolationException`을
  `SEAT_ALREADY_RESERVED`(409)로 바꾼다.
- 트랜잭션 경계는 `create()` 하나다. 만료 정리 → 검증 → INSERT → flush가 그 안에서 일어난다.
  충돌 시 만료 정리까지 함께 롤백되지만 무해하다. 다음 요청이 다시 정리한다.
- pre-check는 경쟁이 없을 때 친절한 응답을 주기 위한 것이고 방어선이 아니다. 검사와 INSERT
  사이의 틈은 원리적으로 막을 수 없고 그 틈을 제약이 막는다.
- 비관적 락은 쓰지 않는다. 좌석 단위로 잠글 행이 없고(좌석 마스터 테이블이 없다),
  `Screening` 행을 잠그면 회차 단위로 직렬화되어 처리량이 크게 떨어진다.

### 결정 4 — 취소 기한은 엔티티에

`Reservation`이 `screening`을 참조하므로 `startAt`과 비교할 재료가 이미 있다. 규칙이 하나뿐이라
정책 객체는 과하다고 보고 `CANCEL_DEADLINE_MINUTES = 20` 상수를 엔티티에 뒀다.

선점(`PENDING`)에는 기한을 적용하지 않는다. 아직 확정 전이라 언제든 놓을 수 있어야 한다.

`now`는 서비스가 주입한다. 엔티티가 `LocalDateTime.now()`를 직접 부르면 "10분 뒤",
"상영 20분 전" 같은 상황을 테스트에서 만들 수 없다. 이를 위해 `Clock` 빈을 도입했다.

### 결정 5 — 권종별 가격

`AudienceType` enum이 할인율을 보유한다. `TheaterType`이 좌석 배치를 갖는 것과 같은 패턴이다.

| 값 | 표시명 | 할인율 | 기준가 14,000 기준 |
|---|---|---|---|
| `ADULT` | 일반 | 0% | 14,000 |
| `YOUTH` | 청소년 | 20% | 11,200 |
| `PREFERENTIAL` | 우대 | 50% | 7,000 |
| `SENIOR` | 경로 | 50% | 7,000 |

`screening.price`가 기준가이고 권종은 거기서 얼마를 깎는지만 안다. 가격표 테이블은 만들지
않았다. 명세에 가격 얘기가 없고 권종은 값이 고정된 소수다.

요청은 좌석마다 권종을 받는다. 화면은 인원을 먼저 고르지만, 좌석-권종 매핑이 없으면 좌석별
금액을 정할 수 없다. 실제 티켓에도 좌석마다 권종이 찍힌다.

### 결정 6 — 좌석 수와 인원 수 검증

좌석마다 권종이 붙으므로 불일치가 구조적으로 발생하지 않는다. 별도 검증 대신 총 좌석 수
상한만 DTO에서 `@Size(max = 8)`로 막았다. 요청 형식 검증이라 Bean Validation이 맞는 자리다.

### API

| 메서드 | 경로 | 구분 | 하는 일 |
|---|---|---|---|
| POST | `/api/reservations` | 수정 | 좌석 선점. `PENDING` 생성 + `expiresAt` |
| POST | `/api/reservations/{id}/payment` | 신규 | mock 결제. 성공 → 확정 / 실패 → 좌석 해제 + 402 |
| DELETE | `/api/reservations/{id}` | 수정 | 취소. 선점은 즉시, 확정은 상영 20분 전까지 |
| GET | `/api/reservations/{id}` | 수정 | 상태·시각 4종·좌석별 권종/금액 |
| GET | `/api/screenings/{id}/seats` | 수정 | 점유 판정에 미만료 선점 포함 |
| GET | `/api/screenings` | 수정 | 잔여좌석 집계를 같은 기준으로 |

### 계획과 달라진 점

- **만료된 선점을 결제하면 정리까지 하려 했으나 예외만 던진다.** `CustomException`이
  트랜잭션을 롤백시켜 같은 트랜잭션에서 한 해제가 사라지기 때문이다. 정리는 그 회차의 다음
  좌석 선점 요청이 맡고, 그 전까지도 조회 조건이 시각을 보므로 좌석은 이미 풀린 것으로 센다.
- **결제 실패 경로만 `@Transactional(noRollbackFor = CustomException.class)`를 쓴다.**
  실패를 402로 알리면서 좌석 해제는 남겨야 해서다.
- **`EXPIRED` 상태의 예매를 취소하면 `RESERVATION_EXPIRED`를 준다.** `ALREADY_CANCELLED`로
  뭉치면 메시지가 사실과 다르다.
- **좌석 라벨 변환을 `ReservationSeat.label()` 한 곳으로 모았다.** 세션 0에서 적어둔
  `ReservationResponse`와 `ScreeningService`의 중복이 이번에 양쪽 다 수정 대상이 되어 함께
  정리했다.

### 남은 것

- 회차 조회 응답 구조는 세션 3에서 재설계하므로 이번에는 점유 판정 기준만 맞췄다.
- 매점(`purchase` / `purchase_product`) 서술은 세션 5 대상이라 그대로 뒀다.

### 확인

`./gradlew test` 70개 통과 (예매 서비스 21 + 예매 API 22).

`README.md`에도 반영했다. ERD와 `reservation`/`reservation_seat` 컬럼 표,
`ReservationStatus`·`AudienceType` ENUM 설명, 중복 예매 방지·선점 만료 설계 배경,
한계 항목(취소 이력 → `release_key`의 의미와 만료 행 잔존).

---

## 세션 3: 회차 조회 API 재설계

세션 1의 `TheaterType`(종류가 좌석 배치를 보유)과 세션 2의 좌석 점유 구조를 전제로 한다.

### 문제

기존 `GET /api/screenings`는 평면 리스트 + 옵션 필터 3개(`movieId`/`branchId`/`date`)뿐이었다.
CGV는 진입점이 둘이고(영화 먼저 / 극장 먼저) 회차 목록 화면에는 상영관 종류 탭·날짜 탭·시간대
탭이 있으며, 회차는 지점 안에서 상영관 종류로 묶여 표시된다. 기존 API로는 이 중 어느 화면도
그릴 수 없었다.

### 결정 1 — 진입점 두 개를 한 API로

영화별 예매와 극장별 예매는 사용자가 어느 필터를 먼저 채웠는지만 다르고 둘 다 "조건에 맞는
회차 목록"으로 수렴한다. 별도 API로 두면 같은 쿼리가 파라미터 이름만 바꿔 두 벌이 된다.

선택 화면을 받치는 보조 API는 따로 뒀다. 지역별 극장 수와 예매율 순 영화 목록이다.

### 결정 2 — 시그니처와 응답 구조

```
GET /api/screenings
  ?movieId=1
  &branchIds=1,2,3      # 극장 복수 선택. 반복 파라미터도 같게 바인딩된다
  &date=2024-06-01      # 생략하면 오늘
  &theaterType=IMAX     # 생략하면 전체 탭
  &timeSlot=EVENING     # 생략하면 전체 탭
```

응답은 화면 그대로 **지점 → 상영관 종류 → 회차** 2단 그룹이다.

- 지점은 이름 오름차순(화면과 같다), 상영관 종류는 `TheaterType` 선언 순서, 회차는 시작 시각순
- 상영관 종류는 `varchar`로 저장돼 DB가 정렬하면 알파벳순이 된다. 선언 순서를 지키려고
  `TreeMap`으로 서비스에서 다시 묶는다
- 회차 카드에 영화를 남겼다. 극장부터 고르는 경로에서는 영화를 고르기 전까지 여러 편이 섞인다
- **오늘이면 이미 시작한 회차를 뺀다.** 예매할 수 없는 카드를 남길 이유가 없다.
  조회 시작 시각을 `max(구간 시작, 현재)`로 잡아 지난 날짜·지난 시간대까지 한 식으로 처리한다
- 날짜 탭 6일치는 클라이언트가 오늘부터 계산한다. 서버가 따로 줄 정보가 없다

**구현 중 걸린 것**: JPQL은 빈 컬렉션 바인딩을 허용하지 않아 `IN :branchIds`를 조건부로 끌 수
없다. `filterByBranch` 플래그로 조건 자체를 끄고 파라미터에는 자리만 채웠다.

### 결정 3 — 잔여석은 매 조회 계산

비정규화 컬럼은 세션 2 구조와 양립하지 않는다. 선점 만료가 시각 의존이라 10분이 지나면 아무도
손대지 않아도 잔여석이 늘어야 하는데 컬럼은 그 순간을 모른다. 반영하려면 배치가 필요하고
그건 범위 밖이다.

세션 2의 `countOccupiedByScreeningIds()`를 그대로 쓴다. 회차 id를 모아 `GROUP BY` 한 방이라
N+1이 아니다. 총석은 `TheaterType.getTotalSeatCount()`이고 카드에 `soldOut`을 같이 내린다.

### 결정 4 — 지역 목록 API 신설

`GET /api/branches/regions`. `Region`이 enum이라 지역 테이블이 없으므로 `Branch`를 지역으로
묶어 세고(`GROUP BY` 한 번), 응답은 enum 선언 순서로 만든다. 집계에 없는 지역은 0으로 채워
극장이 없어도 탭 구성이 흔들리지 않게 했다. 목록에서 빼는 상태(`CLOSED`)는 지점 목록과 같은
기준을 따른다.

### 결정 5 — 시간대 경계는 `TimeSlot` enum 한 곳

| 값 | 표시명 | 구간 |
|---|---|---|
| `MORNING` | 오전 | 00–12 |
| `AFTERNOON` | 오후 | 12–18 |
| `EVENING` | 18시 이후 | 18–23 |
| `LATE_NIGHT` | 심야 | 23–24 |

- "전체"는 enum 값이 아니라 **파라미터 미지정**으로 표현한다. `ALL`을 두면 시·종 시각이 없는
  값이 하나 섞여 `startOn`/`endOn`이 의미를 잃는다
- enum이 `startOn(date)` / `endOn(date)`로 경계를 돌려주고 서비스는 그대로 쿼리에 넘긴다.
  컨트롤러·서비스에 시각 비교 분기를 만들지 않는다
- 자정을 넘기는 회차는 다음 날짜 탭에 잡힌다. 심야는 해당 날짜의 23~24시만 본다는 단순화다

### 결정 6 — 예매율은 조회 시점 집계

배치도 비정규화 컬럼도 두지 않는다. **확정(`RESERVED`) 좌석 수**를 `GROUP BY movie`로 한 번에
세고 서비스에서 정렬한다. 동점은 최신 개봉순으로 가른다.

- 선점(`PENDING`)은 아직 결제 전이라 세지 않는다
- 취소·만료분은 `status`가 `RESERVED`가 아니게 되므로 자동으로 빠진다
- `MovieResponse.reservedSeatCount`로 정렬 근거를 드러냈다

### API

| 메서드 | 경로 | 구분 | 하는 일 |
|---|---|---|---|
| GET | `/api/screenings` | 수정 | 통합 회차 조회. 5개 필터, 2단 그룹 응답 |
| GET | `/api/branches/regions` | 신규 | 지역 탭 + 지역별 극장 수 |
| GET | `/api/movies` | 수정 | 예매율 내림차순, `reservedSeatCount` 추가 |
| GET | `/api/screenings/{id}/seats` | 유지 | 세션 2에서 점유 기준을 이미 맞췄다 |

삭제된 엔드포인트는 없다. 교차 필터(영화로 극장 거르기, 극장으로 영화 거르기)는 범위에서 뺐다.

### 범위에서 뺀 것

- **상영 포맷(2D/3D)**은 새 컬럼을 만들지 않고 `TheaterType`으로 대체했다. 화면의 상영관 종류
  탭과 기준이 같아져 오히려 일관된다
- 미사용 상태였던 `ScreeningRepository.findByTheaterIdOrderByStartAtAsc()`를 제거했다

### 확인

`./gradlew test` 86개 통과 (회차 서비스 13 + 회차 API 12 + 영화 API 5 + 지점 API 12).

---

## 세션 3-1: 조회 쿼리 정리

PR 리뷰 피드백 "`create()`를 한 번 실행했을 때 나가는 SQL을 비교하고 불필요한 SELECT가 없는지
확인하라"에 대한 처리. 세션 4 착수 전에 끝냈다.

### 문제

`create()`는 `findByIdWithTheaterType()`으로 `theater`만 fetch join 하는데, 응답을 만드는
`ReservationResponse.ScreeningSummary.from()`이 `movie.title`과 `theater.branch.name`을 읽는다.
둘 다 LAZY `@ManyToOne`이라 **DTO 변환 시점에 프록시 초기화 SELECT가 두 건 더 나갔다.**
서비스 코드만 보면 보이지 않고 로그를 찍어야 드러나는 종류의 낭비다.

좌석 2개 예매 기준 SQL 9건 → **7건**.

| # | SQL | 비고 |
|---|---|---|
| 1 | SELECT screening + movie + theater + branch | 조인 확장 |
| 2 | SELECT users | 존재 검증(`USER_NOT_FOUND`)의 근거라 남는다 |
| 3 | SELECT 만료 선점 | 유니크 인덱스가 만료 시각을 모른다 |
| 4 | SELECT 점유 좌석 | pre-check |
| 5-7 | INSERT reservation 1 + reservation_seat 2 | |
| ~~+2~~ | ~~SELECT movie / SELECT branch~~ | **제거** |

### 결정 1 — 회차 조회를 용도별로 둘로 나눈다

기존 쿼리를 넓히지 않고 `ScreeningRepository.findByIdWithDetails()`를 새로 뒀다.
좌석 조회(`getSeats()`)는 `theater.theaterType`(enum 컬럼)만 읽으므로 movie·branch를 더하면
그쪽이 over-fetch가 된다. 한 쿼리로 합치면 두 화면 중 하나는 반드시 손해를 본다.

이름이 내용과 어긋나 있던 `findByIdWithTheaterType`은 `findByIdWithTheater`로 바꿨다.
세션 1-1에서 `TheaterType`이 enum 컬럼이 된 뒤로 "타입을 함께 가져온다"는 뜻이 사라졌다.

### 결정 2 — fetch join 쿼리의 `DISTINCT` 제거

Hibernate 6부터 컬렉션 fetch join의 엔티티 중복은 항상 메모리에서 제거되고, HQL의 `DISTINCT`는
SQL로 그대로 전달된다(`passDistinctThrough` 옵션 자체가 없어졌다). 중복 제거 효과는 그대로인데
조인 결과 전체에 대한 SQL `DISTINCT` 비용만 남으므로 뺐다.

### 결정 3 — 취소는 전용 쿼리를 쓴다

`cancel()`은 좌석 해제와 `screening.startAt` 비교만 하고 응답을 만들지 않는다.
`findByIdWithDetails`를 그대로 쓰면 영화·지점까지 조인해 읽지도 않을 컬럼을 끌고 온다.
`findByIdWithSeats`(screening + seats)를 따로 뒀다. 쿼리 수는 1회로 같고 조인 폭만 줄었다.

### 결정 4 — `user`는 조인하지 않는다 (가정이 틀렸던 부분)

당초 `JOIN FETCH r.user`가 필요하다고 봤다. 필드 접근 매핑이면 프록시의 id getter가 단축되지
않아 `user.getId()`가 초기화를 부를 것이라 판단했기 때문이다. **실측 결과 틀렸다.**
조인을 빼도 단건 조회 SQL은 1건 그대로였다. Hibernate는 필드 접근이어도 식별자 getter를
가로채 초기화 없이 값을 돌려준다.

응답이 사용자를 id로만 쓰므로 조인을 뺐다. 이름 같은 다른 필드를 응답에 실으면 그때 다시
fetch join을 더해야 하고, 그 사실을 쿼리 위 주석으로 남겼다.

### 회귀 테스트

`ReservationQueryCountTest`. 테스트 설정에만 `hibernate.generate_statistics=true`를 켜고
`Statistics.getPrepareStatementCount()`로 SQL 수를 센다.

- `create()`(좌석 2개) = 7건, `getById()` = 1건
- 수정 전 코드에서 `create()`가 9건으로 실패하는 것을 먼저 확인하고 고쳤다

DTO에 필드가 늘어 LAZY 초기화가 다시 끼어들면 이 테스트가 잡는다. 로그를 눈으로 대조하지
않아도 되게 만드는 것이 목적이다.

### 검토했으나 하지 않은 것

- **`userRepository.findById()` → `getReferenceById()`** — 존재 검증을 잃는다. FK 위반이
  `DataIntegrityViolationException`으로 올라와 `SEAT_ALREADY_RESERVED`로 잘못 번역된다.
- **pre-check 쿼리를 요청 좌석으로 좁히기** — 가져오는 행의 상한이 총 좌석 수(최대 264)이고
  `screening_id` 인덱스 한 번의 스캔이다. `getSeats()`와 공유 중인 쿼리를 쪼갤 만한 이득이 없다.
- **INSERT 배치(`hibernate.jdbc.batch_size`)** — 좌석 PK가 `IDENTITY`라 생성 키를 행마다
  받아야 해서 배치가 걸리지 않는다.
- `ScreeningService.search()`, `MovieService`, `BranchService`는 목록 + `GROUP BY` 집계
  2쿼리 구조라 이미 N+1이 없다. `default_batch_fetch_size: 100`도 이미 설정돼 있다.

### 확인

`./gradlew test` 88개 통과 (기존 86 + 신규 2). 엔티티·DTO·컨트롤러와 API 응답은 그대로다.

---

## 세션 3-2: 예매 단건 조회 over-fetch 정리

세션 3-1이 **쿼리 수**를 줄였다면 이번은 한 건당 **읽는 양**을 줄인다.
`getById()`가 `findByIdWithDetails()`로 엔티티 6종을 통째로 가져오고 있었다.

### 문제

응답 `ReservationResponse`가 쓰는 값과 SELECT가 읽는 컬럼의 차이:

| 엔티티 | SELECT | 응답이 쓰는 것 |
|---|---|---|
| reservation | 9 | 7 |
| screening | 8 | 3 (id, startAt, endAt) |
| movie | 9 | 1 (title) |
| theater | 6 | 1 (name) |
| **branch** | 9 (**description TEXT** + image_url 포함) | 1 (name) |
| reservation_seat | 10 × N | 4 × N |

두 낭비가 겹친다.

1. **컬럼 폭** — `Branch.description`은 교통·주차 안내를 담는 TEXT(최대 64KB)인데
   응답은 지점 **이름**만 쓴다.
2. **행 증폭** — `LEFT JOIN FETCH r.seats`는 좌석 수만큼 행을 만든다. Hibernate가
   엔티티를 메모리에서 합치기 전에 좌석 1개당 헤더 41개 컬럼이 한 번씩 전송되므로,
   같은 description을 좌석 수(최대 8)만큼 읽는다.
3. 덤으로 영속성 컨텍스트에 엔티티 `5 + N`개와 더티 체킹 스냅샷이 남는다.
   조회는 변경 감지가 필요 없는데도 그렇다.

### 결정 1 — 조회는 엔티티를 쓰지 않는다

`ReservationDetailRow`(record, 좌석 1행) + `ReservationRepository.findDetailRowsById()`
생성자 표현식 프로젝션. `ReservationResponse.of(rows, now)`가 조립한다.
헤더 값은 행마다 반복되지만 이제 전부 스칼라다.

```
before: 41개 컬럼(TEXT 포함) × N행 + 엔티티 5+N개
after : 17개 컬럼            × N행 + 엔티티 0개
```

SQL은 1건 그대로다. `r.user.id`는 FK 컬럼이라 users 조인이 생기지 않는다(3-1 결정 4와 같은 근거).
좌석 정렬도 `ORDER BY`로 DB가 하므로 DTO의 `Comparator`가 이 경로에서는 빠진다.

### 결정 2 — 좌석 조인은 INNER JOIN

좌석은 `@NotEmpty @Size(max = 8)`이라 예매에 항상 1개 이상 있다. INNER JOIN이어도 예매가
있으면 행이 비지 않으므로, **빈 결과 = 예매 없음**이 되어 `RESERVATION_NOT_FOUND` 판정이
행 수 하나로 끝난다. 취소·만료로 풀린 좌석도 이력으로 응답에 남아야 하므로 `release_key`
조건은 넣지 않는다(기존과 동일).

### 결정 3 — 변경 경로(`create`, `pay`)는 엔티티를 유지한다

상태 전이는 관리 상태 엔티티가 있어야 한다. `pay()`를 프로젝션으로 바꾸면 변경용 조회와
응답용 조회가 나뉘어 SELECT가 1건에서 2건이 된다. 결제는 예매당 한 번뿐이라 이득이 없다.
`findByIdWithDetails`는 이제 `pay()` 전용이다.

만료 판정(`PENDING && now >= expiresAt`)은 프로젝션에 엔티티가 없어
`Reservation.isExpired()`를 부를 수 없다. `ReservationResponse`의 private static
`resolveStatus()`로 빼고 엔티티 경로도 같은 메서드를 쓰게 했다. 엔티티는 건드리지 않았다.

### 회귀 테스트

`ReservationQueryCountTest.예매_단건_조회는_SQL_1회로_끝난다`에
`statistics.getEntityLoadCount()`가 0이라는 단언을 더했다. 변경 전에는 6(= 5 + 좌석 1)이
나오는 것을 먼저 확인했다. SQL **수**는 기존 단언이, 읽는 **양**은 이 단언이 지킨다.
조회가 다시 엔티티를 타면 여기서 걸린다.

실제 SQL에서 `description` / `image_url` / `director` / `age_rating`이 사라진 것을 확인했다.

### 검토했으나 하지 않은 것

- **`pay()`도 프로젝션화** — 결정 3. SELECT 1→2건.
- **`Branch.description`에 `@Basic(fetch = LAZY)`** — 바이트코드 인핸스먼트가 있어야 동작하고
  엔티티 변경이 필요하다. 지점 상세 화면은 어차피 description을 쓰므로 전역으로 미루는 것은
  또 다른 곳에서 추가 SELECT를 만든다.
- **조회를 헤더/좌석 2쿼리로 분리** — 헤더 반복이 사라지지만 SQL이 2건이 된다. 반복되는 값이
  스칼라뿐이라 남은 중복의 크기가 작다.

### 확인

`./gradlew test` 88개 통과. 엔티티·컨트롤러·API 응답은 그대로다.

---

## 세션: 좌석 경합 시 커넥션 풀 보호 + 데드락 제거

### 배경

"같은 좌석에 요청이 몰리면 유니크 제약을 확인하는 과정에서 대기가 생기는가,
그때 커넥션과 다른 요청의 응답 시간은 어떻게 되는가"를 확인하다 시작했다.

InnoDB는 중복 키 INSERT를 즉시 1062로 거절하지 않는다. 선행 트랜잭션이 끝날 때까지
S 락을 걸고 블로킹한다. 그 대기 스레드는 **커넥션을 쥔 채로** 기다린다.
설정이 전부 기본값이어서 `maximumPoolSize=10`, `connectionTimeout=30초`,
`innodb_lock_wait_timeout=50초` 였다. 좌석 하나의 경합으로 커넥션 10개가 묶이면
11번째 요청부터는 엔드포인트와 무관하게 30초 뒤 죽는다. 락 한도가 커넥션 한도보다 길어서
**경합과 무관한 API가 경합 중인 API보다 먼저 죽는** 역전이 있었다.

### 결정 1 — 비관적 락은 넣지 않는다

이 스키마에는 Seat 테이블이 없어서 예매 전 좌석에는 **잠글 행 자체가 없다.**
결국 `Screening` 행에 `FOR UPDATE`를 거는 수밖에 없는데, 그건 회차 전체를 직렬화하는 것이라
지금의 좌석 단위 유니크 제약보다 처리량이 훨씬 나쁘다.
유니크 제약에 맡기는 방식 자체는 이 설계에 맞는 선택이다.
고칠 것은 락 전략이 아니라 **락의 범위와 커넥션 점유**였다.

### 결정 2 — 만료 선점 정리를 "요청한 좌석을 막고 있는 것"으로 좁힌다

`findExpiredHolds` → `findExpiredHoldsBlocking`. 회차의 만료 선점을 전부 푸는 대신
요청한 좌석을 실제로 점유 중인 선점만 고른다.

전에는 A5를 고르는 요청이 J12의 만료 선점까지 UPDATE했다. 그 행 락이 예매 커밋까지
유지되므로, 같은 회차를 골랐을 뿐인 다른 좌석 요청들이 서로를 기다렸다.
**경합 단위가 좌석이 아니라 회차였다.**

(행, 열) 쌍을 IN 절에 넣는 방법이 DB마다 달라 `rowNum * 100 + colNum` 스칼라 하나로 접었다.
상영관 종류의 최대 열 수가 22라 100진 자리에서 겹치지 않는다. 이 키를 만들려면 좌석이
범위 안이어야 하므로 정리를 범위·중복 검증 **뒤로** 옮겼다(3→5번). 검증이 먼저 오는 순서가
읽기에도 낫다.

#### 검토했으나 하지 않은 것: 별도 트랜잭션(REQUIRES_NEW) 분리

정리를 `REQUIRES_NEW` 빈으로 떼어내 락을 즉시 놓게 하는 안을 먼저 구현했다가 되돌렸다.
`ReservationControllerTest.만료된_선점의_좌석은_다시_잡을_수_있다`가 409로 깨졌다.
별도 트랜잭션은 다른 커넥션에서 돌기 때문에 `@Transactional` 통합 테스트의 **미커밋 데이터를
볼 수 없다.** 테스트만의 문제가 아니라 "정리가 호출자 트랜잭션에 참여하지 않는다"는
실제 의미 변화다.

게다가 REQUIRES_NEW는 락 **보유 시간**만 줄일 뿐 락 **범위**는 그대로 둔다.
결정 2가 범위를 직접 줄이므로 그쪽이 본질이었다.

### 결정 3 — 좌석을 정렬해서 INSERT한다

`orderedSeats()`로 (행, 열) 오름차순 정렬 후 `addSeat`을 부른다.
Hibernate는 `hibernate.order_inserts` 미설정 시 컬렉션 순서대로 INSERT하고,
INSERT 순서가 곧 락 획득 순서다. 전에는 `[A1,A2]` 요청과 `[A2,A1]` 요청이 서로를 물고 도는
순환 대기를 만들었다.

`ReservationResponse.from`도 정렬하지만 그건 **응답 표시용**이고, 이건 **INSERT 순서**다.
둘은 다른 문제다.

**실측** — 정렬을 빼고 좌석이 엇갈린 동시 요청 6건을 던지면 성공이 **0건**이다.
전원이 서로를 죽인다. 예외가 새는 정도가 아니라 기능이 무너진다.

### 결정 4 — 데드락·락 타임아웃을 409로 매핑한다

`SEAT_RESERVATION_CONFLICT` 추가. 데드락(1213)과 락 타임아웃(1205)은 Spring에서
`ConcurrencyFailureException` 계열로 번역되어 기존 `catch (DataIntegrityViolationException)`에
**걸리지 않았다.** 그대로 500이 나갔다.

`SEAT_ALREADY_RESERVED`로 뭉치지 않은 이유: 락 타임아웃은 좌석이 팔렸다는 뜻이 아니라
**판정하지 못했다**는 뜻이다. 재시도하면 성공할 수 있어서 안내 문구가 달라야 한다.
결정 3으로 데드락 경로는 사실상 사라지지만 안전망으로 남긴다.

### 결정 5 — 커넥션 풀이 인질로 잡히지 않게 한다

| 항목 | 전 | 후 |
|---|---|---|
| `innodb_lock_wait_timeout` | 50초 | 3초 (JDBC URL `sessionVariables`) |
| Hikari `maximum-pool-size` | 10 | 20 |
| Hikari `connection-timeout` | 30초 | 3초 |
| `open-in-view` | true | false |

락 한도를 커넥션 한도보다 길게 두지 않는 것이 핵심이다. 그래야 무관한 API가 먼저 죽는
역전이 사라진다. `connection-init-sql`이 아니라 JDBC URL로 넣은 이유는 H2 테스트가
그 변수를 모르기 때문이다(테스트 yaml이 datasource를 통째로 덮어쓴다).

`open-in-view: false`는 조회가 전부 fetch join 또는 DTO 프로젝션으로 정리된 뒤라 안전하다.
(이전 세션의 조회 쿼리 정리가 선행 조건이었다.)

### 회귀 테스트

`ReservationConcurrencyTest` 추가. 스레드마다 트랜잭션이 따로 열려야 해서
`ControllerIntegrationTest`(@Transactional)를 상속하지 않고 `@AfterEach`에서 직접 정리한다.

1. 같은 좌석 6건 동시 → 성공 정확히 1건, 실패는 전부 매핑된 CustomException
2. 좌석 순서가 엇갈린 6건 동시 → 성공 1건, 점유 좌석 2개 (수정 전 성공 0건으로 실패 확인)
3. 만료된 선점이 잡고 있던 좌석을 동시 요청 2건이 다시 가져감 → 둘 다 성공

테스트 DB가 H2라 InnoDB의 duplicate-key 블로킹 타이밍까지 재현하지는 못한다.
H2도 행 락 타임아웃 시 `CannotAcquireLockException`을 던져 예외 매핑 검증에는 충분하다.

### 확인

`./gradlew test` 94개 통과(기존 88 + 신규 6). 엔티티는 건드리지 않았다.

### MySQL 실환경 확인

테스트는 H2라 `application.yaml` 설정 4개와 새 JPQL이 한 번도 실행되지 않았다
(`src/test/resources/application.yaml`이 main 설정을 통째로 가린다).
MySQL 8.0.45에 앱을 띄워 직접 확인했다. 기준값은 전역 `innodb_lock_wait_timeout=50`,
격리수준 `REPEATABLE-READ`였다.

**새 JPQL** — 생성 SQL이 의도대로 번역됐다.

```sql
and exists(select 1 from reservation_seat rs1_0
  where rs1_0.reservation_id=r1_0.reservation_id
    and rs1_0.screening_id=?
    and rs1_0.release_key=0
    and ((rs1_0.row_num*100)+rs1_0.col_num) in (?))
```

바인딩 값은 `101`(= 1×100 + 1)이었다.

**범위 축소가 실제로 먹는다** — 1행1열과 5행5열에 선점을 만들고 **둘 다** 만료시킨 뒤
1행1열만 재요청했다.

| 예매 | 좌석 | 요청 후 status | release_key | updated_at |
|---|---|---|---|---|
| 1 | A1 | PENDING → **EXPIRED** | 0 → 1 | 갱신됨 |
| 2 | E5 | **PENDING 유지** | **0 유지** | **요청 전과 동일** |

만료 시각이 지났어도 **요청하지 않은 좌석의 선점은 건드리지 않는다.**
전에는 둘 다 풀면서 E5 행에도 UPDATE 락을 걸었다.

**락 타임아웃 + 예외 매핑** — MySQL 세션에서 같은 좌석 행을 INSERT하고 12초간 커밋하지 않은
상태로 앱에 같은 좌석을 요청했다.

- 소요 **3.32초** (전역 50초도, 세션이 쥔 12초도 아님) → `sessionVariables`가 먹었다
- **409 `SEAT_RESERVATION_CONFLICT`** → `ConcurrencyFailureException` catch가 동작.
  이 catch가 없었으면 500이었다

이 측정으로 "InnoDB는 중복 키 INSERT를 즉시 거절하지 않고 커넥션을 쥔 채 블로킹한다"는
전제도 실증됐다.

**`open-in-view: false`** — GET 11개(지역·키워드 필터, 지점 상세, 회차 목록·좌석, 예매 단건)와
쓰기 경로(결제·취소)를 모두 태웠다. 전부 2xx, 로그에 `LazyInitializationException` **0건**.

**풀이 인질로 잡히지 않는다** — 같은 좌석으로 동시 30건을 던지면서 무관한 `/api/movies`를 쟀다.

- 부하 중 앱 DB 커넥션 **20개** (`maximum-pool-size: 20` 적용 확인)
- `/api/movies` 계속 **200, 8~9ms** — head-of-line blocking 없음
- 예매 30건 결과 **201 정확히 1건 + 409 29건**, 500 **0건**
- 전 구간 `SQLTransientConnectionException` **0건**

전체 로그 집계는 `SEAT_ALREADY_RESERVED` 58건, `SEAT_RESERVATION_CONFLICT` 1건이었다.
경합 대부분은 pre-check와 유니크 제약에서 깔끔히 걸러지고, 락 타임아웃은 위 인위적 시나리오
하나뿐이었다.

---

## 세션 4: 찜 API

`BranchLike`/`MovieLike` 엔티티와 리포지토리만 있고 API가 없었다. CGV 극장 카드·상세와 영화의
별 아이콘(누르면 찜, 다시 누르면 해제)을 받칠 API를 만든다. **엔티티는 수정하지 않았다.**
유니크 제약(`uk_branch_like_user_branch`, `uk_movie_like_user_movie`)이 이미 걸려 있었다.

극장 찜과 영화 찜은 커밋을 나눈다. 공통 작업(ErrorCode, 예외 핸들러, 아래 결정)은 극장 찜에 포함했다.

### 결정 1 — 토글 대신 POST(등록) + DELETE(해제)

토글은 "현재 상태를 뒤집어라"라서 결과가 서버 상태에 의존한다. 응답이 유실돼 재시도하면
찜 → 해제로 되돌아가므로 클라이언트가 재시도해도 되는지 판단할 수 없다. POST/DELETE는 요청이
**도달할 최종 상태**를 말하므로 같은 요청을 몇 번 보내도 결과가 같다. 별 아이콘 UI는 현재 상태를
이미 알고 있어 어느 쪽을 부를지 고르는 비용이 없다.

### 결정 2 — 이미 찜한 걸 찜 / 안 한 걸 해제 → 둘 다 200

요청한 최종 상태가 이미 성립해 있으면 목표 달성으로 본다. 409를 주면 클라이언트가 할 수 있는 건
"다시 동기화"뿐이고 재시도 안전성도 깨진다.

- POST는 대상·사용자 존재를 검증한다(404). FK 위반도 `DataIntegrityViolationException`이라
  검증이 없으면 없는 극장이 경합 충돌로 잘못 번역된다(세션 3-1과 같은 근거).
- DELETE는 검증하지 않는다. 없는 극장이면 찜도 없으므로 "찜 아님"이 이미 성립한다.
- 폐관 극장 찜은 막지 않는다. 상세 화면이 폐관도 보여주는 정책이고, 찜 목록에 상태를 싣는다.
- POST가 201이 아닌 200인 이유: 새로 만들었는지가 응답에 의미 없고(멱등) 찜 행의 URI도 노출하지 않는다.

### 결정 3 — 중복 방지는 두 곳에서 막힌다

| 상황 | 막는 곳 | 응답 |
|---|---|---|
| 순차 중복 (재시도, 느린 더블클릭) | `existsBy...` pre-check | 200 (no-op) |
| 진짜 동시 (INSERT 두 건이 겹침) | 유니크 제약 → `DataIntegrityViolationException` | 409 `LIKE_REQUEST_CONFLICT` |
| 락 대기 타임아웃 | `ConcurrencyFailureException` | 409 `LIKE_REQUEST_CONFLICT` |

**동시 경합에서 진 쪽에 200을 줄 수 없다.** flush 중 제약 위반이 나면 Hibernate 세션과 바깥
트랜잭션이 rollback-only가 된다. 예외를 삼키고 정상 반환하면 커밋에서 `UnexpectedRollbackException`
(500)이 난다. 별도 트랜잭션(REQUIRES_NEW / NOT_SUPPORTED)으로 빼면 `@Transactional` 통합 테스트의
미커밋 데이터를 못 본다(좌석 경합 세션에서 겪은 문제).

그래서 `SEAT_RESERVATION_CONFLICT`와 같은 성격으로 뒀다. "실패가 아니라 판정 못 함, 재시도하면
성공". 재시도는 pre-check에 걸려 200으로 수렴한다. 극장·영화 공용 코드 하나다.

해제는 JPQL 벌크 DELETE다. 파생 `deleteBy...`는 SELECT 후 엔티티별로 지워서, 동시 해제로 이미
사라진 행을 만나면 낙관적 락 예외가 난다. 벌크 DELETE는 0행이어도 정상이다. SELECT도 없어진다.

### 결정 4 — `userId`는 세 엔드포인트 모두 쿼리 파라미터

DELETE 본문은 HTTP 의미가 정의돼 있지 않아 일부 클라이언트·프록시가 버린다. POST만 본문으로 받으면
다음 주에 비워질 Request DTO를 새로 만드는 셈이라 셋을 맞췄다. Security 이후 파라미터만 걷어낸다.

이 과정에서 `MissingServletRequestParameterException`이 처리되지 않아 필수 파라미터 누락이 **500**으로
나가던 것을 발견했다. `GlobalExceptionHandler`에서 400 `INVALID_INPUT_VALUE`로 매핑했다.

### 결정 5 — 내 찜 목록 API를 둔다

결정 6에서 목록 응답에 찜 여부를 넣지 않으므로, 이게 없으면 찜 상태를 **읽을 경로가 없다.**
CGV 극장 탭의 "자주 가는 CGV" 영역과 같다.

- 최근 찜한 순(`id DESC`), `JOIN FETCH` 1쿼리
- `status`를 싣는다. 찜한 뒤 폐관된 극장을 조용히 빼면 사용자는 왜 사라졌는지 모른다
- 없는 사용자면 빈 배열이 아니라 `USER_NOT_FOUND`. 잘못된 id가 "찜 없음"으로 숨지 않게
- DTO 프로젝션(세션 3-2)은 쓰지 않았다. 표시명이 enum 메서드라 생성자 표현식에 못 넣어 Row record가
  하나 더 필요하고, 찜 수는 많아야 수십 건이다. 지점 목록 API도 엔티티를 그대로 읽는다

### 결정 6 — 목록 응답에 "내가 찜했는지"는 이번에 넣지 않는다

넣으려면 공개 GET(`/api/branches`, `/api/movies`)에 `userId`를 붙여야 하고, 다음 주 principal로
바뀌면서 시그니처가 한 번 더 바뀐다. 익명이면 `liked`가 null/false 삼중 상태가 된다. 지금은 결정 5의
목록으로 클라이언트가 찜 id 집합을 한 번 받아 별을 칠한다(요청 1회 추가, N+1 없음).

Security 이후 넣을 때의 방법: 목록 id를 모아
`SELECT bl.branch.id FROM BranchLike bl WHERE bl.user.id = :userId AND bl.branch.id IN :ids`
한 번 → `Set<Long>` → `contains`. `specialTypesByBranchId()`와 같은 "id 모아 한 방" 패턴이라 쿼리는 1건만 는다.

### API (극장)

| 메서드 | 경로 | 요청 | 응답 | 에러 |
|---|---|---|---|---|
| POST | `/api/branches/{branchId}/likes` | `?userId=` | 200 (이미 찜이어도) | 400 · 404 `USER_NOT_FOUND` · 404 `BRANCH_NOT_FOUND` · 409 `LIKE_REQUEST_CONFLICT` |
| DELETE | `/api/branches/{branchId}/likes` | `?userId=` | 200 (찜 없어도) | 400 |
| GET | `/api/branches/likes` | `?userId=` | 200 `List<BranchLikeResponse>` | 400 · 404 `USER_NOT_FOUND` |

`/api/branches/likes`는 리터럴 경로라 `/{id}`보다 우선한다(`/regions`와 같다).
찜 API는 `BranchLikeController`/`BranchLikeService`로 분리했다. 조회 전용인 `BranchService`에
쓰기와 `UserRepository` 의존이 섞이지 않게.

### 회귀 테스트 (극장)

- `BranchLikeControllerTest` 10개 — 등록·목록, 중복 등록 200 + 행 1개, 해제, 찜 없는 해제 200,
  남의 찜은 안 지워짐, 404 3종, `userId` 누락 400, 최근 순 + 폐관 극장 상태 표시
- `BranchLikeConcurrencyTest` 2개 — 같은 찜 6건 동시 → 행 정확히 1개, 실패는 전부
  `LIKE_REQUEST_CONFLICT`. 진 요청도 재시도하면 성공. 로그에서 유니크 제약 위반이 실제로 발생해
  catch 경로를 탔음을 확인했다

### 확인 (극장)

`./gradlew test` 106개 통과(기존 94 + 신규 12).

### API (영화)

결정 1~6을 그대로 따른다. 극장 찜과 구조가 대칭이다.

| 메서드 | 경로 | 요청 | 응답 | 에러 |
|---|---|---|---|---|
| POST | `/api/movies/{movieId}/likes` | `?userId=` | 200 (이미 찜이어도) | 400 · 404 `USER_NOT_FOUND` · 404 `MOVIE_NOT_FOUND` · 409 `LIKE_REQUEST_CONFLICT` |
| DELETE | `/api/movies/{movieId}/likes` | `?userId=` | 200 (찜 없어도) | 400 |
| GET | `/api/movies/likes` | `?userId=` | 200 `List<MovieLikeResponse>` | 400 · 404 `USER_NOT_FOUND` |

`MovieLikeResponse`는 `movieId`, `title`, `genre`, `releaseDate`, `ageRating`, `likedAt`.
예매율(`reservedSeatCount`)은 싣지 않았다. 찜 목록은 정렬 기준이 찜한 시각이라 쓸 곳이 없고,
넣으면 `GROUP BY` 집계 쿼리가 하나 더 붙는다.

### 회귀 테스트 (영화)

- `MovieLikeControllerTest` 10개. 극장 찜과 같은 항목에서 폐관 상태 대신 최근 순만 확인한다
- 동시성 테스트는 두지 않았다. 서비스 구조와 제약 방식이 극장과 같아 `BranchLikeConcurrencyTest`가
  같은 경로를 검증한다

### 확인 (영화)

`./gradlew test` 116개 통과(극장 찜까지 106 + 신규 10). 엔티티는 건드리지 않았다.

---

## 세션 5: 매점 구매 API

`store` 도메인에 엔티티·리포지토리만 있었다. 과제 명세(극장별 재고, 공통 메뉴, 환불 없음)와
운영진 확인 불변식 **"재고는 어떤 시점에도 1 이상"**을 반영해 메뉴 조회·구매·구매 내역 API를 만들었다.

이미 정해져 있어 유지한 것: 메뉴는 `Product`(공통), 재고는 `Stock`(극장 × 상품),
`Purchase`-`PurchaseProduct` 헤더-디테일, 구매 시점 가격의 `unitPrice` 복사, 취소 API 없음.

### 결정 1 — 재고 불변식은 엔티티가 1차, DB CHECK가 최종선

`Stock`만 수정했다(`Purchase`·`PurchaseProduct`·`Product`는 그대로).

| 위치 | 규칙 | 위반 시 |
|---|---|---|
| 생성자 | `quantity >= 1` | `INVALID_STOCK_QUANTITY` (400) |
| `decrease(amount)` | `amount >= 1` | `INVALID_INPUT_VALUE` — 음수 차감은 재고를 늘리는 버그 |
| `decrease(amount)` | `amount <= availableQuantity()` | `OUT_OF_STOCK` (409). 검사를 끝낸 뒤에만 값을 바꿔 실패 시 상태 불변 |
| DB | `CONSTRAINT ck_stock_quantity_min CHECK (quantity >= 1)` | 엔티티를 우회한 쓰기(수동 SQL, 향후 벌크 쿼리) 차단 |

- **재고 N이면 판매 가능 수량은 N-1**이다. 의도된 동작이고 `availableQuantity()` / `isSoldOut()`으로
  엔티티가 이 규칙을 소유한다. 메뉴 응답과 차감이 같은 메서드를 본다
- CHECK는 JPA 3.2 표준 `@Table(check = @CheckConstraint(...))`로 선언했다. MySQL은 **8.0.16부터**
  CHECK를 실제로 적용한다(그 전엔 파싱만 하고 무시). 운영 DB는 8.0.45(좌석 경합 세션에서 실측)라 유효하다.
  `ddl-auto: create`라 마이그레이션이 필요 없었다
- 재고 등록/보충 API는 명세에 없어 만들지 않았다

### 결정 2 — 구매는 단일 단계

좌석은 "고른 뒤 결제까지 남이 못 잡게" 점유가 필요했지만 매점 상품은 대체 가능한 수량이라 점유할
대상이 없다. PENDING을 두면 선점 때 재고를 빼고 만료 때 되돌리는 **세션 2의 만료 문제가 그대로
재현**되는데 얻는 것이 없다. 환불도 없으니 `Purchase`에 상태 필드가 필요 없고, 실패한 구매는 기록을
남기지 않는다.

### 결정 3 — 동시성: 비관적 락, 상품 id 순으로 한 건씩

**트랜잭션 경계**는 `PurchaseService.purchase()` 하나다.

```
1. 요청 안 중복 상품 검사, 상품 id 오름차순 정렬
2. 사용자·지점 조회, 운영 상태 확인                    락 없음
3. 상품 일괄 조회 (findAllById)                          락 없음, 가격·이름용
4. 상품마다 순서대로
     SELECT ... FROM stock WHERE branch_id=? AND product_id=? FOR UPDATE   ← 락 획득
     stock.decrease(qty)
5. mock 결제 판정 — 실패면 예외 → 전체 롤백
6. Purchase + items INSERT, stock UPDATE → 커밋 시 락 해제
```

- **락 범위**: 해당 지점 × 요청 상품의 stock 행만. `uk_stock_branch_product`의 동등 조회라 InnoDB는
  레코드 락만 건다. 다른 지점, 같은 지점의 다른 상품은 서로 막지 않는다
- **product는 잠그지 않는다.** 락 쿼리에 `JOIN FETCH s.product`를 넣으면 MySQL `FOR UPDATE`가 조인된
  product 행까지 잠가 **지점이 달라도 같은 상품 구매가 직렬화**된다. 상품은 3단계에서 따로 읽는다.
  실제 SQL에서 `from stock s1_0 where ... for update`로 stock만 잠기는 것을 확인했다
- **데드락 방지**: `IN` 한 방으로 잠그면 획득 순서가 옵티마이저의 인덱스 스캔 순서에 맡겨진다. 정렬 후
  한 건씩 잠가 순서를 코드로 보장했다. 대가는 상품 종류 수만큼의 SELECT인데 한 주문에 한 자릿수다
- **락 보유 시간**: 4단계부터 커밋까지. mock 결제라 즉시 끝난다. 실제 PG였다면 외부 호출 동안 락을 쥐게
  되므로 결제를 트랜잭션 밖으로 빼는 설계가 필요하다. 범위 밖이라 기록만 남긴다
- **락 대기 타임아웃**(`innodb_lock_wait_timeout=3`) → `ConcurrencyFailureException` →
  `STOCK_LOCK_CONFLICT`(409). 좌석의 `SEAT_RESERVATION_CONFLICT`처럼 "품절이 아니라 판정 못 함, 재시도"다
- 좌석에서 비관적 락을 거절한 이유는 **잠글 행이 없어서**였다. 재고는 경합 단위(지점 × 상품)와 정확히
  일치하는 행이 있으므로 비관적 락이 맞다. 인기 상품은 충돌이 잦아 낙관적 락은 재시도가 폭주하고
  `@Version` 컬럼(엔티티 변경)도 필요하다
- 불변식이 엔티티 메서드에 있으므로 조건부 벌크 UPDATE(`quantity - :n >= 1`)는 쓰지 않았다

### 결정 4 — 요청 검증

| 경우 | 응답 | 근거 |
|---|---|---|
| 같은 상품 두 번 | 400 `DUPLICATE_PRODUCT_IN_REQUEST` | 합치지 않고 거부. 장바구니는 상품당 한 줄이라 중복은 클라이언트 버그다. 합치면 요청과 저장 내역이 어긋난다. `DUPLICATE_SEAT_IN_REQUEST`와 같은 판단 |
| 수량 0 이하, 항목 없음, 필수값 누락 | 400 `INVALID_INPUT_VALUE` | Bean Validation(`@Min(1)`, `@NotEmpty`, `@NotNull`) |
| 없는 상품 id | 404 `PRODUCT_NOT_FOUND` | `findAllById` 결과 개수 비교 |
| 상품은 있으나 그 극장 재고 행이 없음 | 409 `OUT_OF_STOCK` | 그 극장에서 팔지 않는 상품. 고객에게는 품절과 같다 |
| 판매 가능 수량 초과 | 409 `OUT_OF_STOCK` | `Stock.decrease()` |
| 휴관·폐관 지점 | 409 `BRANCH_NOT_OPERATING` | 기존 `Branch.isReservable()`(OPEN만 true). 예매 쪽에는 아직 이 검사가 없다 |

### 결정 5 — API

| 메서드 | 경로 | 요청 | 응답 | 에러 |
|---|---|---|---|---|
| GET | `/api/branches/{branchId}/products` | — | 200 `List<StoreMenuResponse>` 상품 id 순 | 404 `BRANCH_NOT_FOUND` |
| POST | `/api/purchases` | body `userId`, `branchId`, `items[{productId, quantity}]`, `paymentResult` | 201 `PurchaseResponse` | 400 `INVALID_INPUT_VALUE` / `DUPLICATE_PRODUCT_IN_REQUEST` · 404 `USER_NOT_FOUND` / `BRANCH_NOT_FOUND` / `PRODUCT_NOT_FOUND` · 409 `BRANCH_NOT_OPERATING` / `OUT_OF_STOCK` / `STOCK_LOCK_CONFLICT` · 402 `PURCHASE_PAYMENT_FAILED` |
| GET | `/api/purchases` | `?userId=` | 200 `List<PurchaseResponse>` 최근 순 | 400 · 404 `USER_NOT_FOUND` |

- **메뉴**: `Stock JOIN FETCH product WHERE branch = ?` 한 방. 지점 확인 포함 **SQL 2건, 상품 수와 무관**.
  원재고는 내리지 않고 판매 가능 수량과 품절 여부만 준다. 휴관 지점도 메뉴는 보여준다(지점 상세와 같은 정책)
- **구매 내역**: DTO 프로젝션(`PurchaseHistoryRow`, 세션 3-2 방식). 엔티티로 fetch join하면
  `branch.description`(TEXT)이 **구매 수 × 항목 수**만큼 반복 전송된다. 항목은 구매마다 1개 이상이라 INNER JOIN.
  `PurchaseResponse.listOf()`가 `LinkedHashMap`으로 묶어 `ORDER BY p.id DESC` 순서를 유지한다
- **구매 응답**은 방금 만든 엔티티에서 바로 만든다. 추가 SELECT 없음
- 컨트롤러는 `StoreMenuController`(`/api/branches/{id}/products`)와 `PurchaseController`로 나눴다.
  메뉴 경로가 branches 아래지만 재고를 읽으므로 store 도메인에 둔다

### 결정 6 — mock 결제는 예매의 `PaymentResult`를 재사용

- 예매는 결제가 별도 호출이라 enum을 `PaymentRequest`로 받았다. 매점은 단일 단계라 구매 요청 본문에
  `paymentResult`로 받는다. enum은 `reservation.dto.PaymentRequest.PaymentResult`를 그대로 import한다
  (dto 참조는 도메인 규칙상 허용, service만 금지)
- **재고를 확보한 뒤 결제를 판정한다.** 결제부터 하면 돈은 나갔는데 품절인 경우가 생긴다
- FAILURE → `PURCHASE_PAYMENT_FAILED`(402) → **전체 롤백**. 예매의 `noRollbackFor`와 반대다. 예매는 실패해도
  좌석 해제를 남겨야 했지만 매점은 남길 것이 없다
- 기존 `PAYMENT_FAILED`는 문구가 "좌석 선택부터 다시"라 예매 전용이다. 문구를 바꾸면 예매 응답이 바뀌므로
  새 코드를 뒀다

### ErrorCode 추가

`INVALID_STOCK_QUANTITY`(400), `DUPLICATE_PRODUCT_IN_REQUEST`(400), `BRANCH_NOT_OPERATING`(409),
`STOCK_LOCK_CONFLICT`(409), `PURCHASE_PAYMENT_FAILED`(402). `OUT_OF_STOCK`, `PRODUCT_NOT_FOUND`는 기존 것.

### 회귀 테스트

| 클래스 | 수 | 내용 |
|---|---|---|
| `StockTest` | 5 | 생성 시 1 미만 거부, 1개 남기는 차감, 1 미만 만드는 차감 거부 + 값 불변, 음수 차감 거부, N-1 |
| `StoreMenuControllerTest` | 4 | 판매 가능 수량·품절, 극장별 분리, 404, **상품 3개에서 SQL 2건**(Statistics, N+1 회귀) |
| `PurchaseControllerTest` | 13 | 성공 201 + 차감 + unitPrice·총액, 1개 남기는 구매, 초과 409, 중복 400, 수량 0 400, 빈 항목 400, 없는 상품 404, 재고 행 없음 409, 휴관 409, 없는 사용자 404, 결제 실패 402, 내역 최근 순, 내역 404 |
| `PurchaseRollbackTest` | 3 | 부분 성공 없음(A 차감 후 B 실패 → A 원복), 결제 실패 시 재고 원복·기록 없음, **DB CHECK가 우회 쓰기를 막음** |
| `PurchaseConcurrencyTest` | 2 | 재고 11(판매 가능 10)에 20스레드 동시 구매 → 성공 정확히 10, 나머지 전부 `OUT_OF_STOCK`, 최종 재고 1 / [A,B]·[B,A] 엇갈린 10스레드 → 전원 성공 |

**롤백 검증은 실제 트랜잭션에서 한다.** `@Transactional` 테스트 안에서는 서비스가 바깥 테스트 트랜잭션에
참여하므로 롤백이 테스트 끝까지 미뤄지고, 차감된 값이 영속성 컨텍스트에 남아 "원복됐다"를 확인할 수 없다.
그래서 부분 성공·결제 실패는 `ReservationConcurrencyTest`처럼 비트랜잭션 + `@AfterEach` 정리로 분리했다.

CHECK 검증 테스트는 `EntityManager`를 직접 써서 Spring 예외 번역을 거치지 않는다. 기대 예외는
`DataIntegrityViolationException`이 아니라 Hibernate `ConstraintViolationException`이고, 메시지에 제약 이름이
실리는 것까지 확인한다.

동시성 테스트는 3회 반복 실행해 모두 통과했다. 테스트 DB는 H2라 InnoDB의 레코드 락 범위까지 재현하지는
않는다. 검증하는 것은 lost update가 없는지와 획득 순서가 고정되는지다.

### 검토했으나 하지 않은 것

- `Purchase.purchasedAt`을 `Clock` 주입으로 변경 — 시각 기반 로직이 없어 테스트 이득 없이 엔티티만 바뀐다
- 구매 단건 조회, 내역 페이지네이션 — 요청 범위 밖
- `IN` 한 방 잠금 — 획득 순서를 코드로 보장할 수 없다
- 조건부 벌크 UPDATE — 불변식을 엔티티 밖으로 빼게 된다

### 확인

`./gradlew test` 143개 통과(기존 116 + 신규 27). 생성 DDL에
`constraint ck_stock_quantity_min check (quantity >= 1)`이 들어가는 것을 확인했다.

---

## 3주차 세션 1: 회원가입 / 로그인 / Access Token 발급·검증

3주차 JWT 인증의 첫 세션. 사용자 생성 → 비밀번호 인증 → 토큰 발급·검증까지만 만들었다.
토큰을 요청에서 꺼내 SecurityContext에 넣는 필터와 보호 경로 구분은 세션 2에서 한다.
그래서 이번 검증 로직의 목표는 "다음 필터가 만료 / 변조 / 형식 오류를 서로 다른 코드로 응답할 수 있는 구조"다.

### 버전 확인

| 항목 | 버전 | 확인 방법 |
|---|---|---|
| Spring Boot | 4.1.1 | `build.gradle` |
| Spring Security | 7.1.1 | Boot BOM |
| JJWT | 0.13.0 | Maven Central 최신 안정판 |

- JJWT는 0.12 이후 API만 쓴다(`Jwts.parser().verifyWith().build().parseSignedClaims()`, `Jwts.builder().subject()`).
  `parserBuilder()`, `setSigningKey()`, `setSubject()` 계열은 쓰지 않는다
- JJWT에 Jackson 3 모듈이 없어 `jjwt-jackson`(Jackson 2)을 썼다. Boot 4 BOM이 Jackson 2(2.21.5)도 관리한다
- Security 7의 `DaoAuthenticationProvider`는 `UserDetailsService`를 **생성자로만** 받는다(`setUserDetailsService` 제거).
  `setPasswordEncoder`는 jar에서 deprecated가 아님을 `javap`로 확인했다

### 결정 1 — AuthenticationManager는 직접 조립한다

```java
DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
provider.setPasswordEncoder(passwordEncoder);
return new ProviderManager(provider);
```

`AuthenticationConfiguration.getAuthenticationManager()`도 같은 조립을 하지만, Spring이 빈을 찾아 연결하므로
"어떤 Provider가 어떤 UserDetailsService·PasswordEncoder로 비교하는지"가 코드에 보이지 않는다.

로그인 호출 순서:

```
AuthService.login()                                     ← 우리 코드
 └ authenticationManager.authenticate(unauthenticated token)
    └ ProviderManager → DaoAuthenticationProvider       ← Spring
       ├ LoginUserDetailsService.loadUserByUsername()   ← 우리 구현, Spring이 호출
       ├ passwordEncoder.matches(raw, hash)             ← Spring이 호출
       └ 성공 → authenticated token, eraseCredentials()
 └ jwtProvider.createAccessToken()                      ← 우리 코드
```

### 결정 2 — 로그인 실패는 단일 응답

- `DaoAuthenticationProvider`는 `hideUserNotFoundExceptions=true`(기본)라 `UsernameNotFoundException`을
  `BadCredentialsException`으로 바꾼다. 계정이 없을 때도 더미 해시로 `matches()`를 돌려(`mitigateAgainstTimingAttack`)
  응답 시간 차이를 줄인다
- `AuthService.login()`에서 `BadCredentialsException`만 `LOGIN_FAILED`(401)로 바꾼다
- `AuthenticationException` 전체를 잡지 않는다. `InternalAuthenticationServiceException`(DB 장애 등)이
  "비밀번호 틀림"으로 가려지면 안 된다
- 로그인 요청에는 가입 형식 규칙을 적용하지 않고 `@NotBlank`만 둔다. 400과 401이 갈리면 규칙·존재 여부를 추측할 단서가 된다

두 응답을 다르게 주면 아이디 목록을 대입해 가입된 계정만 추려내는(계정 열거) 공격이 가능해지고,
추려낸 계정에 비밀번호 대입을 집중할 수 있다.

### 결정 3 — UserDetails는 둘로 나눈다

| 타입 | 쓰이는 곳 | 비밀번호 |
|---|---|---|
| `LoginUserDetails` (`UserDetails`, `CredentialsContainer`) | 로그인 한 번 | 해시 보유, 인증 후 `ProviderManager`가 지움 |
| `AuthUser(userId, role)` record | JWT 검증 결과, 세션 2 필터의 principal | 필드 자체가 없음 |

하나로 합치면 JWT 경로에서 `getPassword()`에 쓰면 안 되는 빈 값을 채워야 한다.
`LoginUserDetails`가 userId를 들고 있어 인증 직후 재조회 없이 `sub`를 만든다.

**구현 중 걸린 것**: Security 7은 인증 성공 시 권한에 `FACTOR_PASSWORD`를 덧붙인다(다중 인증 지원).
토큰에는 `role`만 싣기 때문에 이 권한은 로그인 요청 밖으로 나가지 않는다. 테스트에서 이 동작을 명시했다.

### 결정 4 — 토큰 검증 결과는 `AuthUser` 또는 유형별 `CustomException`

| 실제 발생한 JJWT 예외 | 상황 | ErrorCode |
|---|---|---|
| `ExpiredJwtException` | 만료 | `EXPIRED_TOKEN` |
| `security.SignatureException` | payload 변조, 다른 키, HS512 | `INVALID_TOKEN` |
| `UnsupportedJwtException` | `alg: none` | `INVALID_TOKEN` |
| `IncorrectClaimException` | 다른 `iss` | `INVALID_TOKEN` |
| `MalformedJwtException` | 조각 수·Base64·JSON 오류 | `MALFORMED_TOKEN` |
| `IllegalArgumentException` | null, 빈 문자열, 공백 | `MALFORMED_TOKEN` |

- 세션 2 필터는 `catch (CustomException e)` → `e.getErrorCode()`로 응답을 고른다. 필터는
  `@RestControllerAdvice` 밖이라 어차피 직접 잡아야 한다
- JJWT는 서명을 먼저 검증하고 그다음 exp·iss를 본다. **만료 + 변조 토큰은 `INVALID_TOKEN`** 이 나온다.
  "만료" 판정은 우리가 발급한 게 확실한 토큰에만 붙는다
- 허용 알고리즘을 `sig().clear().add(HS256)`로 못박았다. 헤더의 `alg`는 토큰을 만든 쪽이 정하는 값이다
- enum 결과(`TokenStatus`) 반환은 호출자가 VALID 확인을 잊으면 실패 토큰으로 인증이 되므로 택하지 않았다

토큰 구성: `sub`=userId 문자열, `role`=USER|ADMIN(접두사 없음), `iat`, `exp`, `iss`=`cgv-api`(코드 상수). `aud`는 생략.
HS256, 키는 Base64 디코딩 후 256비트 이상이어야 하고, 미만이면 `WeakKeyException`으로 기동이 실패한다.
발급·검증 시각은 기존 `Clock` 빈을 쓴다.

설정은 `jwt.secret: ${JWT_SECRET}`, `jwt.access-token-validity: ${JWT_ACCESS_TOKEN_VALIDITY}`이고 기본값이 없다.
실제 값은 Git에 올리지 않는 `.env`로 주입한다. 테스트 yaml에는 테스트 전용 더미 키를 뒀다.

### 결정 5 — 회원가입 검증

| 필드 | 규칙 | 이유 |
|---|---|---|
| loginId | `^[a-z0-9]{4,20}$` | MySQL 기본 collation이 대소문자를 구분하지 않아 `Abc`/`abc`가 unique에서 충돌한다 |
| password | 공백 없는 ASCII 8~64자 | BCrypt는 72바이트 초과를 거부한다. 한글은 글자당 3바이트라 길이 제한만으로는 못 막는다 |
| name | `@NotBlank @Size(max=50)` | 컬럼 길이 |
| birthDate | `@NotNull @Past` | ISO `yyyy-MM-dd` |
| email | `@NotBlank @Email @Size(max=100)` | unique 없음. 로그인에 쓰지 않는다 |
| phoneNumber | `^01[016789][0-9]{7,8}$` | **하이픈 없이 숫자만** 저장·수신. 표시 형식은 클라이언트 몫 |

- loginId 중복: `existsByLoginId` pre-check + `saveAndFlush`의 `DataIntegrityViolationException` → 409 `DUPLICATE_LOGIN_ID`.
  users의 unique 제약은 `login_id` 하나라 오역 여지가 없다
- `HttpMessageNotReadableException`이 처리되지 않아 `"2000-13-01"` 같은 본문이 **500**으로 나가던 것을 발견해
  400 `INVALID_INPUT_VALUE`로 매핑했다. 전역 핸들러라 다른 API의 JSON 오류에도 적용된다

### 결정 6 — role은 두 겹으로 막는다

1. `SignupRequest`에 `role` 필드가 없다. Jackson이 모르는 필드를 버린다
2. `User` 빌더에 role 파라미터가 없고 생성자가 `Role.USER`로 고정한다

관리자 계정 생성은 세션 3에서 의도가 드러나는 별도 메서드로 추가한다(엔티티 변경이라 그때 승인받는다).
`ROLE_` 접두사는 `Role.getAuthority()` 한 곳에서만 붙인다.

### 엔티티 변경

`User`에 `email`(varchar 100), `phoneNumber`(varchar 11), `role`(enum, not null) 추가. unique는 `login_id`만.
`Role` enum 신설. 기존 `User.builder()` 사용처는 `TestFixtures.user()`, `ReservationServiceTest.userWithId()` 두 곳이었다.

### 패키지 신설 — `global/security`

`JwtProvider`, `JwtProperties`, `AuthUser`, `LoginUserDetails`, `LoginUserDetailsService`.
Spring Security 어댑터 모음이라 도메인 밖에 뒀다. CLAUDE.md 패키지 트리에 없는 새 패키지다.
회원가입·로그인 API는 `domain/user`(`AuthController`, `AuthService`)에 있다.

### 최소 SecurityConfig (임시)

STATELESS, csrf/formLogin/httpBasic/logout 비활성화, `POST /api/auth/signup`·`/api/auth/login` permitAll,
**나머지도 임시 permitAll**(주석 표시). 세션 2에서 `authenticated()`로 바꾼다.

CSRF를 끈 근거는 "인증 수단이 `Authorization` 헤더뿐"이라는 전제다. 쿠키 인증으로 바꾸면 다시 켜야 한다.

`ControllerIntegrationTest`의 MockMvc는 `springSecurity()`를 적용하지 않아 필터 체인을 타지 않는다.
그래서 `SecurityConfigTest`를 따로 두고 필터를 태운 상태로 기존 API 개방, CSRF 없는 POST, 세션 미생성을 확인했다.

### API

| 메서드 | 경로 | 요청 | 응답 | 에러 |
|---|---|---|---|---|
| POST | `/api/auth/signup` | `{loginId, password, name, birthDate, email, phoneNumber}` | 201 `{userId, loginId}` | 400 `INVALID_INPUT_VALUE` · 409 `DUPLICATE_LOGIN_ID` |
| POST | `/api/auth/login` | `{loginId, password}` | 200 `{accessToken, tokenType, expiresIn}` | 400 `INVALID_INPUT_VALUE` · 401 `LOGIN_FAILED` |

### ErrorCode 추가

`DUPLICATE_LOGIN_ID`(409), `LOGIN_FAILED`(401), `EXPIRED_TOKEN`(401), `INVALID_TOKEN`(401), `MALFORMED_TOKEN`(401).
인증 없음·권한 없음 코드는 세션 2에서 추가한다.

### 회귀 테스트

| 클래스 | 수 | 내용 |
|---|---|---|
| `SecurityConfigTest` | 3 | 필터를 태운 상태로 기존 GET 200, CSRF 없는 POST 성공, 세션·쿠키 미생성 |
| `AuthControllerTest` | 22 | 해시 저장 + USER 생성, **본문 `role: ADMIN` 무시**, 같은 비밀번호도 해시 다름, 중복 409, 형식 오류 400(11종), 필수값 누락, 날짜 파싱 실패 400, 로그인 토큰의 sub·role, 가입 후 로그인, **비밀번호 틀림과 계정 없음의 응답 본문 완전 동일**, 빈 값 400, 인증 후 해시 삭제 |
| `JwtProviderTest` | 17 | 왕복, claim이 정확히 5개, 만료·만료 직전, payload 변조, 다른 키, alg=none, HS512, 다른 iss, 만료+변조 → INVALID, 형식 오류 6종, 짧은 키 기동 실패 |

JJWT 예외 분류는 임시 테스트로 입력마다 실제 발생 예외를 찍어 확인한 뒤 지웠다(결정 4의 표).

### 확인

`./gradlew test` 185개 통과(기존 143 + 신규 42). 로컬 실행 시 `.env`에 `JWT_SECRET`, `JWT_ACCESS_TOKEN_VALIDITY`가 필요하다.
`UserDetailsService` 빈이 생기면서 Boot의 `Using generated security password` 로그가 사라졌다.

---

## 3주차 세션 2: JWT 인증 필터 / 보호 경로 / 인증·인가 실패 공통 응답

세션 1의 `JwtProvider.parse()`(성공 → `AuthUser`, 실패 → 유형별 `CustomException`)를 전제로,
요청에서 토큰을 꺼내 SecurityContext에 넣는 필터와 실제 경로 규칙, 401/403 JSON 응답을 만들었다.
세션 1의 임시 `anyRequest().permitAll()`을 걷어냈다.

### 결정 1 — 오류 코드를 과제 명세 이름으로 교체

| 세션 1 | 세션 2 | 상태 |
|---|---|---|
| — | `TOKEN_NOT_EXIST` | 401 |
| `EXPIRED_TOKEN` | `TOKEN_EXPIRED` | 401 |
| `INVALID_TOKEN`, `MALFORMED_TOKEN` | `TOKEN_INVALID` (합침) | 401 |
| — | `ACCESS_DENIED` | 403 |

만료만 따로 둔다. 클라이언트가 재로그인으로 복구할 수 있는 유일한 경우라서다. 변조와 형식 오류를 나눠 알려주면
공격자가 만든 토큰이 "파싱까지는 통과했다"는 단서가 된다. `JwtProvider`의 catch도 만료 / 나머지 두 갈래로 줄였다.

### 결정 2 — 필터는 막지 않고 기록만 한다 (request attribute 방식)

| 방식 | 선택 |
|---|---|
| (a) 필터에서 EntryPoint를 직접 호출하고 체인 종료 | X |
| (b) 실패 원인을 request attribute에 남기고 통과, 보호 경로에서 거부될 때 EntryPoint가 읽음 | **O** |

(a)는 만료 토큰을 가진 클라이언트가 공개 조회 API까지 막힌다. 거부 여부를 정하는 곳이 경로 규칙과 필터
두 군데로 갈린다. (b)는 필터가 "사실"만 기록하고, 거부는 `AuthorizationFilter`가 경로 규칙으로 정한다.

필터가 `CustomException`을 그대로 던지면 안 된다. `ExceptionTranslationFilter`는 `AuthenticationException`과
`AccessDeniedException`만 처리하고, 게다가 JWT 필터는 그보다 앞에 있다. `@RestControllerAdvice`도 닿지 않아
컨테이너까지 올라가 500이 되고, 공개 API도 같이 죽는다.

**README용 문장 — 토큰 검증 실패 정책**

> 토큰 검증에 실패해도 필터는 요청을 바로 막지 않는다. 실패 원인만 기록하고 익명 요청으로 넘기며, 거부 여부는
> 경로 규칙이 정한다. 보호 API는 기록된 원인에 따라 `TOKEN_EXPIRED` 또는 `TOKEN_INVALID`로 401을 받고, 공개 조회
> API는 토큰이 만료되거나 변조됐어도 익명 사용자로 정상 응답한다. 공개 API는 사용자 정보를 쓰지 않으므로 잘못된
> 토큰이 권한을 얻는 경로는 없다. 대신 클라이언트는 공개 API 응답만으로는 토큰 만료를 알 수 없고, 보호 API를
> 호출했을 때 알게 된다.

### 결정 3 — principal은 토큰 클레임만으로 만든다 (DB 재조회 없음)

`AuthUser(userId, role)`가 `UserDetails`를 구현해 principal이 된다. 비밀번호는 `null`, username은 userId 문자열.
세션 1에서 "요청마다 DB를 보지 않는다"를 전제로 설계한 타입이라 별도 `CustomUserDetails` 클래스를 두지 않았다.

- 매 요청 SELECT는 stateless 토큰의 이점을 지운다. 보호 API마다 쿼리가 1건씩 는다
- **한계**: 권한 변경·탈퇴가 토큰 만료 전까지 반영되지 않는다
  - 탈퇴 사용자는 `userId`를 쓰는 서비스에서 `USER_NOT_FOUND`로 걸린다
  - 남는 실질 위험은 "ADMIN 권한을 회수했는데 기존 토큰으로 관리자 API 호출"이다
- 완화 수단은 짧은 유효기간(`JWT_ACCESS_TOKEN_VALIDITY`)뿐이다. Refresh Token·블랙리스트는 영구 제외 범위다

### 결정 4 — 필터는 빈으로 만들지 않는다

Boot는 `Filter` 타입 빈을 전부 서블릿 컨테이너에 자동 등록한다(`ServletContextInitializerBeans`). 필터가 `@Component`면
Security 체인 안(`addFilterBefore`)과 밖(서블릿 필터)에 두 번 등록된다. `SecurityConfig`에서
`new JwtAuthenticationFilter(jwtProvider)`로 만들어 체인에만 넣었다.
`@Component` + `FilterRegistrationBean.setEnabled(false)`는 만들고 다시 끄는 두 단계라 한쪽이 빠져도 드러나지 않아 택하지 않았다.

실측한 체인 순서:

```
 1 DisableEncodeUrlFilter
 2 WebAsyncManagerIntegrationFilter
 3 SecurityContextHolderFilter          요청 끝에 clearContext()
 4 HeaderWriterFilter
 5 JwtAuthenticationFilter              ← addFilterBefore(UsernamePasswordAuthenticationFilter)
 6 RequestCacheAwareFilter
 7 SecurityContextHolderAwareRequestFilter
 8 AnonymousAuthenticationFilter        비어 있으면 익명 토큰
 9 SessionManagementFilter
10 ExceptionTranslationFilter           EntryPoint / AccessDeniedHandler 호출
11 AuthorizationFilter                  경로 규칙 판정
```

`formLogin`을 꺼서 `UsernamePasswordAuthenticationFilter`는 체인에 없지만, `HttpSecurity`의 순서표 기준으로 그 앞 자리를 받는다.
Anonymous 필터보다 앞이어야 토큰 인증이 먼저 자리를 잡는다.

SecurityContext는 `createEmptyContext()`로 새로 만들어 교체한다. 기존 인스턴스를 고치면 그것을 공유하는 다른 스레드에 인증이 샌다.

### 결정 5 — 경로 규칙

| 메서드 | 경로 | 규칙 |
|---|---|---|
| * | `/api/admin/**` | `hasAuthority(Role.ADMIN.getAuthority())` (API는 세션 3) |
| GET | `/api/branches/likes`, `/api/movies/likes` | authenticated |
| POST | `/api/auth/signup`, `/api/auth/login` | permitAll |
| GET | `/api/movies`, `/api/movies/{id}` | permitAll |
| GET | `/api/branches`, `/api/branches/regions`, `/api/branches/{id}`, `/api/branches/{branchId}/products` | permitAll |
| GET | `/api/screenings`, `/api/screenings/{id}/seats` | permitAll |
| * | `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` | permitAll |
| * | `/error` | permitAll |
| * | 그 외 (예매·결제·취소, 찜 등록·해제, 매점 구매·내역) | authenticated |

- 첫 매칭 규칙만 적용된다. `/api/branches/{id}`가 `likes`도 받아들이므로 찜 목록 규칙을 공개 GET보다 먼저 뒀다.
  순서가 바뀌면 MVC는 리터럴 경로를 우선하므로 찜 목록 컨트롤러가 익명에게 열린다
- `hasRole("ADMIN")`은 내부에서 `ROLE_`을 다시 붙인다. 접두사를 `Role.getAuthority()` 한 곳에서만 만들기 위해 `hasAuthority`를 썼다
- 기본값은 `authenticated()`. 규칙 없이 추가된 API는 열리지 않고 잠긴다
- `/error`: 컨테이너 오류 포워드도 인가를 거친다. 막으면 원래 오류가 익명 401로 덮인다

### 결정 6 — 실패 응답

| 상황 | 경로 | 응답 |
|---|---|---|
| 보호 API, 토큰 없음 | 익명 → `AuthorizationFilter` 거부 → `ExceptionTranslationFilter` → EntryPoint (attribute 없음) | 401 `TOKEN_NOT_EXIST` |
| 보호 API, 만료 토큰 | 필터가 `TOKEN_EXPIRED` 기록 → 익명 → EntryPoint | 401 `TOKEN_EXPIRED` |
| 보호 API, 변조·형식 오류 | 필터가 `TOKEN_INVALID` 기록 → 익명 → EntryPoint | 401 `TOKEN_INVALID` |
| 공개 API, 만료·변조 토큰 | 기록만 남고 permitAll 통과 | 200 |
| 관리자 경로, USER 토큰 | 인증됨 → `AuthorizationFilter` 거부 → AccessDeniedHandler | 403 `ACCESS_DENIED` |
| 관리자 경로, 토큰 없음 | 익명 → EntryPoint | 401 `TOKEN_NOT_EXIST` |

- `AuthorizationFilter`는 두 경우 모두 같은 `AccessDeniedException`을 던진다. 401/403을 가르는 것은
  `ExceptionTranslationFilter`가 현재 인증이 익명인지 보는 분기다. 신원을 모르면 권한 없음을 판정할 수 없으므로 401이다
- 401에는 `WWW-Authenticate: Bearer`를 붙인다
- JSON 직렬화는 `SecurityErrorResponder` 한 곳. 필터 단계는 `DispatcherServlet` 전이라 `@RestControllerAdvice`가 잡지 못한다.
  `sendError()`는 Boot 기본 오류 JSON으로 바뀌므로 응답에 직접 쓴다
- 매퍼는 Boot 4 자동설정 빈인 Jackson 3 `tools.jackson.databind.json.JsonMapper`. Jackson 2 `ObjectMapper`는 jjwt-jackson 때문에
  classpath에 있지만 빈이 아니다. `ApiResponse`의 `com.fasterxml.jackson.annotation.JsonInclude`는 Jackson 3도 인식한다
- 서블릿 기본 인코딩이 ISO-8859-1이라 `application/json;charset=UTF-8`을 명시한다

### 결정 7 — CSRF 비활성화 유지

**README용 문장 — CSRF**

> 이 API는 인증 정보를 `Authorization: Bearer` 헤더로만 받고 세션과 인증 쿠키를 쓰지 않는다. CSRF는 브라우저가 쿠키 같은
> 자격 증명을 요청에 자동으로 실어 보내는 점을 악용하는 공격인데, Bearer 헤더는 클라이언트 코드가 명시적으로 넣어야만
> 전송되고 다른 출처의 페이지는 그 토큰을 읽을 수 없어 위조된 요청에 인증이 실리지 않는다. 그래서 CSRF 보호를
> 비활성화했다. 인증 수단을 쿠키로 바꾸면 이 전제가 깨지므로 다시 켜야 한다.

### 결정 8 — Swagger Bearer 스킴

`bearerAuth`(HTTP, bearer, JWT) 스킴과 전역 `SecurityRequirement`를 추가했다. Authorize에 한 번 넣은 토큰이 모든 요청에 실린다.
공개 API에도 실리지만 결정 2에 따라 막히지 않으므로 무해하다. 컨트롤러마다 `@SecurityRequirement`를 다는 방식은 규칙이
`SecurityConfig`와 두 곳에 생겨 택하지 않았다. `/v3/api-docs`가 토큰 없이 200이고 `securitySchemes.bearerAuth`가
들어가는 것을 임시 테스트로 확인했다.

### 남은 것 (세션 3)

보호 API가 여전히 `userId`를 쿼리·본문으로 받는다. 로그인한 A가 `userId=B`로 B의 자원에 접근할 수 있다.
이번 세션은 "인증 여부"까지만 다뤘고, `@AuthenticationPrincipal AuthUser`로 바꾸는 것과 관리자 API는 세션 3에서 한다.

### 회귀 테스트

`SecurityConfigTest` 3 → 11개. `springSecurity()`를 적용한 MockMvc로 필터 체인을 태운다.

| 테스트 | 확인 |
|---|---|
| 공개 API 토큰 없이 / 만료 토큰으로 | 200 |
| 유효 토큰 + CSRF 없는 POST | 200 |
| 보호 API 토큰 없음 | 401 `TOKEN_NOT_EXIST`, `WWW-Authenticate: Bearer`, `application/json;charset=UTF-8`, 한글 메시지 |
| 보호 API 만료 / 변조 | 401 `TOKEN_EXPIRED` / `TOKEN_INVALID` |
| 찜 목록 두 경로 익명 | 401 (규칙 순서 회귀) |
| 관리자 경로 USER / 익명 | 403 `ACCESS_DENIED` / 401 `TOKEN_NOT_EXIST` |
| 세션 미생성 | `Set-Cookie` 없음, 세션 null |
| 필터 단일 등록 | 빈 0개, 체인 안에 정확히 1개 |

만료 토큰은 테스트 `JwtProperties`와 과거 시각 `Clock`으로 만든 `JwtProvider`로 발급한다.
정상·실패 시나리오 전수 테스트는 세션 4에서 한다.

### 확인

`./gradlew test` 193개 통과(기존 185 + 신규 8). `ControllerIntegrationTest`는 `springSecurity()`를 적용하지 않아
기존 컨트롤러 테스트는 영향이 없다.

---

## 3주차 세션 3: 사용자별 접근 제어 / 소유권 검사 / 관리자 API

세션 2까지는 "인증 여부"만 봤다. 보호 API가 여전히 `userId`를 본문·쿼리로 받아서, 로그인한 A가 B의 id를
넣으면 B의 자원을 만들고 읽고 지울 수 있었다. 모든 사용자 식별을 토큰 기준으로 바꾸고, 리소스 id로 대상을
지정하는 예매에 소유권 검사를 넣고, 관리자 API와 관리자 계정 초기화를 만들었다.

### 결정 1 — 남의 예매는 404 `RESERVATION_NOT_FOUND` (ErrorCode를 추가하지 않는다)

세션 프롬프트는 "ErrorCode를 추가"하라고 했지만 따르지 않았다. `ApiResponse.code`가 `ErrorCode.name()`이라
404로 응답해도 코드가 다르면 "남의 것"이라는 사실이 그대로 드러난다. 없는 예매와 **응답 본문이 완전히 같아야**
존재를 숨길 수 있다. 예매 id가 IDENTITY 순차 증가라 403이면 id를 차례로 넣어 어떤 예매가 존재하는지,
예매량이 얼마인지 알아낼 수 있다. 테스트가 두 응답 본문의 문자열 동일성까지 단언한다.

서버도 둘을 구분하지 않는다(다음 결정에서 쿼리 조건으로 걸러지므로). 감사 로그가 필요해지면 그때 조건 없는
존재 조회를 추가한다.

### 결정 2 — 소유권 검사는 쿼리 조건

```sql
WHERE r.id = :id AND r.user.id = :userId
```

`findOwnedWithDetails`(결제), `findOwnedWithSeats`(취소), `findOwnedDetailRows`(조회). 조건 없는 기존 세 쿼리는 지웠다.

- 남의 예매는 **로딩되지 않는다.** `cancel()`/`confirm()`을 부를 엔티티가 없으므로 "소유권 검사가 상태 변경보다
  먼저"가 코드 순서가 아니라 구조로 보장된다
- 특히 결제 실패 경로가 위험했다. `noRollbackFor = CustomException`이라 상태 변경 뒤의 어떤 예외도 변경을
  되돌리지 않는다. 검사를 줄 순서에 맡기면 한 줄만 어긋나도 남의 좌석이 풀린 채 커밋된다
- 남의 **이미 취소된** 예매를 취소해도 409가 아니라 404다. 상태 검사가 먼저면 409가 존재와 상태를 흘린다
- `r.user.id`는 FK 컬럼이라 users 조인이 붙지 않는다. SQL 수는 그대로다(`ReservationQueryCountTest` 7건/1건 유지)

버린 대안
- `reservation.validateOwner(userId)` — 엔티티 변경이 필요하고, "없는 척하라"는 **API 노출 정책**을 엔티티가
  `NOT_FOUND`를 던지는 식으로 표현하게 된다. 조회는 프로젝션 경로라 엔티티 메서드를 쓸 수도 없다
- 서비스 `if`문 — 호출부마다 반복되고, 빠뜨리거나 `cancel()` 뒤에 써도 컴파일·테스트가 잡지 못한다

**구현 전 코드로 새 테스트를 먼저 돌려 공격이 성립하는 것을 확인했다.**

| A의 토큰으로 B의 예매에 | 수정 전 | 수정 후 |
|---|---|---|
| `DELETE /api/reservations/{id}` | 200, 취소됨 | 404, 상태 불변 |
| `POST .../payment {"result":"FAILURE"}` | 402, **B의 좌석 해제가 커밋됨** | 404, PENDING 유지 |
| 이미 취소된 예매에 `DELETE` | 409 `ALREADY_CANCELLED` | 404 |
| `GET /api/reservations/{id}` | 200, 전부 열람 | 404, 없는 예매와 같은 본문 |

찜·구매 내역에는 별도 소유권 검사가 필요 없다. 대상을 `(토큰 사용자, branchId)` 같은 **자연 키**로 지정하므로
남의 자원을 가리킬 문법이 없다. 리소스 id로 지정하는 API(예매)만 검사가 필요하다.

### 결정 3 — `ROLE_` 접두사는 `Role.getAuthority()` 한 곳 (세션 1 결정 유지)

| 위치 | 값 |
|---|---|
| 토큰 `role` 클레임 | `ADMIN` (접두사 없음, `role.name()`) |
| `AuthUser.getAuthorities()` | `ROLE_ADMIN` (`role.getAuthority()`) |
| `SecurityConfig` | `hasAuthority(Role.ADMIN.getAuthority())` |

`hasRole("ADMIN")`은 `AuthorityAuthorizationManager`가 `"ROLE_" + "ADMIN"`을 만들어 `hasAuthority`와 같은 문자열
비교를 한다. `AdminControllerTest`가 두 매니저를 직접 만들어 같은 결과를 내는 것을 확인한다.
토큰에 `ROLE_ADMIN`을 넣지 않는 이유: 클레임은 도메인 값이고 접두사는 Spring Security의 표현이다. 섞으면
`Role.valueOf("ROLE_ADMIN")`이 실패하거나 `hasRole`에서 접두사가 두 번 붙는다.

### 결정 4 — 관리자 계정은 `ApplicationRunner` + `@Profile("local")` + 환경변수

- data.sql은 BCrypt 해시를 Git에 박아야 한다. 해시도 오프라인 대입 대상이다. 러너는 기동 시 `PasswordEncoder`로 해시한다
- `admin.login-id: ${ADMIN_LOGIN_ID}`, `admin.password: ${ADMIN_PASSWORD}`. 기본값 없음
- `@ConfigurationProperties` + `@Validated @NotBlank` record로 받고, `@EnableConfigurationProperties`를
  **러너 클래스에** 달았다. 러너가 local 프로필에서만 뜨므로 바인딩도 local에서만 일어난다.
  다른 프로필은 값이 없어도 기동되고, local인데 비어 있으면 기동이 실패한다
- `@Profile("local")`: 운영에서 부팅 부수효과로 관리자가 생기면 안 된다. 운영 관리자 생성은 명시적인 운영 작업이어야 한다
- 멱등: `existsByLoginId`면 건너뛴다
- 관리자 생성은 `User.createAdmin(...)` 정적 팩토리(**엔티티 변경, 승인받음**). 빌더에는 여전히 role이 없다
- 회원가입으로 관리자를 만들 수 없는 것은 재확인했다(`SignupRequest`에 role 없음, 빌더가 USER 고정,
  `AuthControllerTest`의 본문 `role: ADMIN` 무시 테스트)

로컬 실행 시 `.env`에 `SPRING_PROFILES_ACTIVE=local`, `ADMIN_LOGIN_ID`, `ADMIN_PASSWORD`가 필요하다.

### 결정 5 — 서비스에는 `Long userId`만 넘긴다

컨트롤러가 `@AuthenticationPrincipal AuthUser`에서 `userId()`만 꺼낸다. 서비스가 인증 방식을 모르므로
동시성·롤백 테스트는 여전히 Long을 넘기고, 찜 서비스는 **한 줄도 바뀌지 않았다.**
`@AuthenticationPrincipal(expression = "userId")`는 SpEL 문자열이라 필드 이름 변경을 컴파일러가 잡지 못해 택하지 않았다.

`AuthenticationPrincipalArgumentResolver`는 principal을 파라미터 타입에 대입할 수 없으면(익명의 `"anonymousUser"`)
예외 없이 null을 준다. 이 API들이 모두 `authenticated()`라 null이 컨트롤러까지 오지 않는다. 경로를 실수로
`permitAll`로 열면 401이 아니라 NPE → 500이 된다. 각 API에 "토큰 없으면 401" 테스트를 둔 이유다.

### 결정 6 — 내 예매 내역 `GET /api/reservations`

- URL에 사용자가 없다. `/api/users/{userId}/reservations`면 경로 id와 토큰 id를 비교하는 검사가 또 필요하다
- 단건 조회와 같은 프로젝션 + `ORDER BY r.id DESC, 좌석`. `ReservationResponse.listOf()`가 `LinkedHashMap`으로 묶고
  단건용 `of()`를 재사용한다. 만료 판정이 단건 조회와 같은 코드를 탄다
- 취소·만료 예매도 상태와 함께 포함한다. 방금 취소한 예매가 사라지면 확인할 곳이 없다
- 사라진 사용자의 토큰은 `USER_NOT_FOUND`. 찜·구매 내역과 같은 기준
- SQL은 예매 수와 무관하게 **2건**(사용자 확인 + 내역), 엔티티 로드 0

### API

| 메서드 | 경로 | before | after |
|---|---|---|---|
| POST | `/api/reservations` | body `{screeningId, userId, seats}` | body `{screeningId, seats}` + Bearer |
| POST | `/api/reservations/{id}/payment` | — | Bearer, 남의 예매 404 |
| GET | `/api/reservations/{id}` | — | Bearer, 남의 예매 404 |
| DELETE | `/api/reservations/{id}` | — | Bearer, 남의 예매 404 |
| GET | `/api/reservations` | 없음 | **신규** 내 예매 내역 |
| POST/DELETE/GET | `/api/branches/{id}/likes`, `/api/branches/likes` | `?userId=` | Bearer |
| POST/DELETE/GET | `/api/movies/{id}/likes`, `/api/movies/likes` | `?userId=` | Bearer |
| POST | `/api/purchases` | body에 `userId` | body에서 제거 + Bearer |
| GET | `/api/purchases` | `?userId=` | Bearer |
| GET | `/api/admin/check` | 없음 | **신규** USER 403 / ADMIN 200 / 없음 401 |

예전 형식으로 `userId`를 보내도 오류 없이 무시된다. Jackson은 모르는 본문 필드를, Spring MVC는 선언하지 않은
쿼리 파라미터를 버린다. 테스트가 본문에 남의 `userId`를 실어도 토큰 사용자로 처리되는 것까지 확인한다.
springdoc은 `@AuthenticationPrincipal` 파라미터를 문서에서 뺀다. `/v3/api-docs`에 `userId`·`authUser`가 없는 것을
임시 테스트로 확인하고 지웠다.

### 테스트 구조 변경

`ControllerIntegrationTest`가 `springSecurity()`를 적용한다. 모든 컨트롤러 테스트가 실제 필터 체인을 탄다.

- `bearer(User)` — `RequestPostProcessor`라 `perform()` 시점에 토큰을 발급한다. `@MockitoBean Clock`으로 시간을
  몇 시간씩 밀어도 발급·검증이 같은 시계를 봐서 만료되지 않는다
- `missingUser()` — DB에 없는 id 9999의 사용자. 탈퇴 후에도 만료 전까지 유효한 토큰을 흉내 낸다
- 공개 경로 규칙이 빠지면 이제 해당 컨트롤러 테스트가 401로 깨진다. 전에는 필터를 안 타서 통과했다
- 커밋 1에서는 예매 테스트만 임시로 필터를 태웠고 커밋 3에서 기반 클래스로 옮겼다(찜·구매 테스트가 아직 `userId`를 쓰고 있었다)

| 클래스 | 추가·변경 |
|---|---|
| `ReservationControllerTest` | 소유권 4(취소·결제 실패·재취소·조회 본문 동일성), 본문 `userId` 무시, 토큰 없음 401, 사라진 사용자 404, 내역 4 |
| `ReservationServiceTest` | 남의 예매 결제 → `RESERVATION_NOT_FOUND` |
| `ReservationQueryCountTest` | 내역 SQL 2건 + 엔티티 로드 0 |
| `PurchaseControllerTest` | 본문 `userId` 무시(남의 내역 0건), 토큰 없음 401 |
| 찜 컨트롤러 테스트 2개 | `userId` 누락 400 → 토큰 없음 401 |
| `AdminControllerTest` | ADMIN 200, USER 403, 없음 401, `hasRole`/`hasAuthority` 동등성 |
| `AdminAccountInitializerTest` | 해시된 ADMIN 생성, 재실행 멱등, **로그인 → 토큰 → `/api/admin/check` 200**, 빈 비밀번호 기동 실패(`BindValidationException`), local 아니면 러너 없음 |

`AdminAccountInitializerTest`는 러너가 테스트 트랜잭션 밖에서 커밋하므로 `@Transactional` 대신 `@AfterAll`에서
관리자 행을 지운다. 프로필·검증 조건은 `ApplicationContextRunner`로 러너와 바인딩만 띄워 확인한다.

### 확인

`./gradlew test` 216개 통과(기존 193 + 신규 23). 엔티티 변경은 `User.createAdmin()` 하나다.

---

## 3주차 세션 4: 인증·인가 시나리오 테스트 / README

과제의 인증·인가 시나리오 표를 1:1로 옮긴 통합 테스트와 README 인증·인가 섹션을 만들었다. 운영 코드는 바꾸지 않았다.

### 결정 1 — 정상 토큰은 로그인 API 응답에서 받는다

기존 컨트롤러 테스트의 `bearer(User)`는 `JwtProvider`를 직접 부른다. 시나리오 테스트는 `AuthScenarioTest`(신규 추상 클래스)의
`signup()` → `login()`으로 API를 거쳐 토큰을 받는다. 비밀번호 비교부터 토큰 생성까지 운영 경로를 한 번에 탄다.
관리자만 회원가입으로 만들 수 없어 `User.createAdmin` + `PasswordEncoder`로 저장하고, 로그인은 API로 한다.

`@WithMockUser`는 쓰지 않았다. SecurityContext를 미리 채워 헤더 파싱·서명·만료·claim 변환·EntryPoint를 전부 건너뛴다.
이번 시나리오는 전부 그 구간에서 일어난다. principal도 `User`라 `@AuthenticationPrincipal AuthUser`가 null이 된다.

### 결정 2 — 실패 토큰은 실제 `JwtProvider`에 설정만 바꿔 발급한다

| 토큰 | 만드는 법 | 검증하는 것 |
|---|---|---|
| 만료 | 같은 `JwtProperties` + 발급 시각을 유효기간보다 1분 더 과거로 둔 `Clock` | 서명은 맞고 시간만 지난 토큰 → `TOKEN_EXPIRED` |
| 변조 | 로그인 토큰 payload의 `role`을 USER→ADMIN으로 바꾸고 헤더·서명 유지 | 무결성. 내용이 바뀌면 서명이 맞지 않는다 |
| 다른 키 | secret만 다른 `JwtProperties`로 만든 `JwtProvider` | 발급 주체. 내용과 서명이 서로 맞아도 우리 키가 아니면 거부 |

- `Jwts.builder()`로 직접 조립하지 않았다. claim 이름·iss·알고리즘을 테스트가 따로 알아야 하고, 차이가 한 가지(시각 또는 키)로 좁혀지지 않는다
- 변조 토큰은 `/api/admin/check`로 보낸다. 200(위조 권한 통과), 403(서명 무시하고 claim만 읽음), 401 `TOKEN_INVALID`(정답)가 갈린다
- 서명 마지막 글자 바꾸기는 base64url 패딩 비트 때문에 같은 바이트로 디코딩될 수 있어 버렸다

### 결정 3 — 인증 비유지는 ThreadLocal과 쿠키·세션 두 경로를 본다

MockMvc는 모든 요청을 테스트 스레드 하나에서 처리하고, 브라우저와 달리 쿠키를 자동으로 보내지 않는다.

1. 첫 요청(정상 토큰) 200 직후 `SecurityContextHolder`가 비어 있는지 본다
2. 첫 응답의 쿠키·세션을 직접 실어 두 번째 요청을 보내 401 `TOKEN_NOT_EXIST`
3. 그 뒤에 쿠키·세션이 없었음을 단언한다. 앞에 두면 실패 시 2의 행위 검증이 실행되지 않는다(계획에서 순서만 바꿨다)

임시로 JWT 필터를 `SecurityContextHolderFilter` **앞에** 두고 돌려 봤다. 인증이 새는 게 아니라 `setDeferredContext()`가
덮어써 **사라진다**. 첫 요청의 200 단언에서 실패했다. 1번 단언은 컨텍스트를 세팅하는 곳이 `clearContext()`의
try/finally 밖에 있는 경우(스레드풀 재사용 시 다음 사용자에게 인증이 샘)를 겨냥한다.

### 결정 4 — 소유권 테스트에 대조군을 둔다

B가 자기 토큰으로 선점·결제한 예매를 A의 토큰으로 취소 → 404 `RESERVATION_NOT_FOUND` → `flushAndClear()` 후
`findById`로 `RESERVED`, `cancelledAt` null, 좌석 2개 `isOccupied()`를 확인한다. 취소해도 좌석 행이 남는 구조라
좌석 수가 아니라 점유 상태를 본다. 마지막에 B 본인이 취소해 200을 받는다. 이게 없으면 "모든 취소를 404로 거부하는"
버그에서도 테스트가 통과한다. 회차는 실제 시각 기준 내일로 두고 `Clock`을 목으로 바꾸지 않았다(컨텍스트 캐시 유지).

권한 상승 방지는 DB role USER → 로그인 토큰의 role claim USER → 관리자 API 403까지 이어서 본다.

### MockMvc가 필터 체인을 타는지 확인

`springSecurity()` 없는 MockMvc로 토큰 없이 `GET /api/reservations`를 보내면 401이 아니라 **500**이다
(principal null → 컨트롤러 NPE). 임시 테스트로 확인하고 지웠다. `TOKEN_*` 코드, `WWW-Authenticate: Bearer`,
`application/json;charset=UTF-8`은 우리 EntryPoint에서만 나오므로 그 단언 자체가 체인을 탔다는 증거다.

### 테스트

| 클래스 | 수 | 내용 |
|---|---|---|
| `AuthenticationScenarioTest` | 10 | 로그인 성공, 로그인 실패 응답 동일(필드별 + 본문 문자열), 공개 API 토큰 없음, 정상 토큰 보호 API, 토큰 없음 401, 만료, 변조, 다른 키, 인증 비유지, 공개 API + 변조 토큰 200 |
| `AuthorizationScenarioTest` | 4 | USER → 관리자 API 403, ADMIN → 200, 남의 예매 취소 거부 + DB 불변 + 주인 취소 성공, 가입 본문 `role: ADMIN` 무시 |

모든 테스트에 `@DisplayName`을 달았다. 메서드명도 기존 관례대로 한글이고 README 표에 그대로 옮겼다.
기존 `SecurityConfigTest`·`AuthControllerTest`·`AdminControllerTest`와 겹치는 검증은 지우지 않았다. 시나리오 테스트는
토큰 출처(로그인 API)와 변조 방식(payload만 변경)이 다르다.

### README

`## 인증·인가`를 README 맨 끝("배운점 및 느낀점" 뒤)에 추가했다. 인증 구조(로그인 흐름·Claim·필터 순서), 공개/보호 경로,
CSRF, 토큰 검증 실패 정책, 오류 코드, 테스트 결과, 로컬 비밀값 설정 방법. 세션 2의 README용 문장을 가져오면서
README 본문 문체(`~습니다`)로 바꿨다. 로컬 설정은 변수 이름과 형식만 쓰고 값은 쓰지 않았다.

기존 README에서 현재 코드와 어긋난 부분도 같은 커밋에서 고쳤다.

| 위치 | 전 | 후 |
|---|---|---|
| ERD `users`, user 테이블 정의 | email·phone_number·role 없음 | 세 컬럼 추가, `Role` 설명(가입은 USER 고정, ADMIN은 `createAdmin`만) |
| 배운점 — ORM | `cancel()`이 `seats.clear()`로 좌석 행 삭제 | `cancel(now)` + `releaseSeats()`, 좌석 행 유지 |
| 배운점 — N+1 | `findByIdWithDetails(id)` | `findOwnedWithDetails(id, userId)`, 소유권 조건 설명 |
| 배운점 — REST | 본문 `userId`, DTO 직접 반환, 취소 204 | `@AuthenticationPrincipal`, `ApiResponse`, 취소 200 |
| 배운점 — 예외 처리 | `ErrorResponse`, 3단계 | `ApiResponse.error`, 필터 단계 실패는 `SecurityErrorResponder` |
| 배운점 — MVC 흐름 | "필터와 인터셉터는 아직 없다" | Security 필터 체인 요약 |
| 배운점 — 레이어드 | 엔티티로 단건 조회 | 소유자 조건 프로젝션 조회 |
| 배운점 — 테스트 예시 | `springSecurity()` 없는 기반 클래스, 본문 `userId` | 현재 기반 클래스(`springSecurity()`, `bearer`)와 `좌석_선점_성공` |

### README — JWT 인증 흐름 정리 (과제 1번)

`## JWT 인증 흐름 정리`를 `## 인증·인가` 바로 앞에 추가했다. 과제 질문 7개를 소제목으로 두고 핵심 → 설명 →
우리 프로젝트에서는(클래스·경로·오류 코드) → 참고(RFC·공식 문서) 순서로 썼다. 개념 설명은 작성자 초안을 따랐고,
초안과 달라진 곳은 다음과 같다.

| 초안 | 반영 | 근거 |
|---|---|---|
| 5번 `CustomUserDetails` | `AuthUser` | 해당 클래스 없음(세션 2 결정 3) |
| 4번 `exp` "필수" | "발급 시 항상 넣는다. 검증에서 존재를 강제하지는 않는다" | 같은 키로 서명한 `exp` 없는 토큰을 `parse()`가 통과시키는 것을 임시 테스트로 확인했다. JJWT는 `exp`가 있을 때만 만료를 검사한다. 키를 가진 쪽이 이 서버뿐이라 현재 위험은 없다 |
| 5번 "`STATELESS`라 요청이 끝나면 비워진다" | "`SecurityContextHolderFilter`가 `clearContext()`로 비운다. `STATELESS`라 세션도 만들지 않는다" | 비우는 주체는 필터다. `STATELESS`는 세션 생성을 막는 설정이다 |
| 1번 헤더에 `alg`, `typ` | 일반 설명은 유지, 우리 토큰 예시는 `{"alg":"HS256"}`만 | JJWT가 `typ`를 넣지 않는 것을 실측했다 |

디코딩 예시는 테스트 설정의 더미 키로 발급한 토큰에서 헤더와 페이로드만 옮겼고, 서명과 키는 싣지 않았다.
확인에 쓴 임시 테스트는 지웠다.

README 테이블 정의의 "회원" 표기는 유지한다. 코드 식별자는 `User`로 통일하되, 문서의 한글 명칭은 "회원"을 써도 된다.

### 확인

`./gradlew test` 230개 통과(기존 216 + 신규 14).

---

## 3주차 세션 5: 리프레시 토큰 (발급 · 재발급 · 거부 · 구분 · 로그아웃 폐기)

도전 과제. 로그인 시 리프레시 토큰을 함께 발급하고, 그것으로 액세스 토큰을 재발급하고, 로그아웃 시 폐기한다.
저장소는 기존 DB. 순환 발급과 재사용 탐지는 다음 세션에서 한다.

### 결정 1 — 리프레시 토큰은 JWT가 아닌 256비트 무작위 문자열

작성자가 정한 방향을 그대로 따랐다. 반박할 근거는 없었고 오히려 함정 하나를 피한다. 같은 키로 서명한 JWT로 만들면
`JwtAuthenticationFilter`가 서명만 보고 통과시킨다. 막으려면 `typ`나 별도 클레임 검사가 필요하고, 한 줄만 빠져도
리프레시 토큰이 액세스 토큰으로 쓰인다.

- `SecureRandom` 32바이트 → 패딩 없는 `Base64URL` 43자. 알파벳에 `.`이 없어 JWT 세 조각이 구조적으로 불가능하다
- `SecureRandom.getInstanceStrong()`은 일부 리눅스에서 엔트로피를 기다리며 멈출 수 있어 쓰지 않았다
- 무작위 문자열에는 만료 정보가 없어 응답에 `refreshTokenExpiresIn`을 준다
- 수명은 `refresh-token.validity: ${REFRESH_TOKEN_VALIDITY}`. `jwt.` 아래에 두지 않았다(JWT가 아니므로). 기본값 없음

### 결정 2 — 해시는 SHA-256, BCrypt가 아니다

- BCrypt는 솔트 때문에 같은 입력도 해시가 매번 달라 **해시로 행을 조회할 수 없다**. 사용자 id를 따로 받아 전부 `matches()`해야 한다
- 솔트·느린 해시는 사람이 고른 추측 가능한 값을 사전 공격에서 지키는 장치다. 256비트 난수는 대입할 후보가 없다
- 이 판단은 토큰 길이에 묶여 있다. 48비트로 줄이면 빠른 해시를 모든 후보에 대입하는 데 하루도 안 걸리고, 솔트가 없어
  한 번 훑으면 유출된 해시 전체가 풀린다
- 서버 비밀키를 섞은 해시(HMAC)는 DB 단독 유출을 한 번 더 막지만, 256비트 입력이라 이득이 없고 관리할 키만 늘어 택하지 않았다

### 결정 3 — 테이블에 순환·재사용 탐지용 컬럼을 미리 두지 않는다

`refresh_token(refresh_token_id, user_id, token_hash char(64) unique, expires_at, revoked_at, created_at, updated_at)`.

- `ddl-auto: create`라 다음 세션에 컬럼을 더해도 이관 비용이 없다
- 로직 없는 컬럼은 채울 규칙이 없거나, 채우는 순간 그게 로직이다. 테스트로 검증할 수도 없다
- 묶음 id냐 부모 토큰 id냐, 사용 완료를 시각으로 두냐 상태 enum으로 두냐는 다음 세션 설계에서 정한다
- 폐기는 행 삭제가 아니라 `revoked_at` 기록. 삭제하면 폐기된 토큰과 없던 토큰을 구분할 수 없고 재사용 탐지의 근거도 사라진다.
  `revoke()`는 이미 폐기된 행의 시각을 덮어쓰지 않는다(멱등). 폐기 시각은 사건 기록이라 공격자가 반복 호출로 갱신할 수 있으면 안 된다
- 엔티티 상태 메서드는 `isUsableAt(now)`, `isRevoked()`, `isExpiredAt(now)`. 계획의 `invalidReasonAt()`(원인이 없으면 null)은
  호출부에서 헷갈리기 쉬워 두 메서드로 나눴고, 로그용 원인 문자열은 서비스가 만든다

### 결정 4 — 여러 기기 허용, 로그아웃은 본문의 토큰 하나만 폐기(공개 경로)

- 로그인마다 행 하나. 새 로그인이 기존 것을 폐기하면 앱 로그인이 웹을 로그아웃시킨다. 행이 쌓이는 대가는 감수(정리 스케줄러는 범위 밖)
- 로그아웃은 액세스 토큰이 만료된 뒤에도 가능해야 한다. 리프레시 토큰 보유 자체를 그 기기의 자격 증명으로 본다
- 없는 토큰, 이미 폐기·만료된 토큰에도 200. 클라이언트는 어차피 토큰을 버리고, 로그아웃이 유효성 확인 창구가 되지 않는다
- 토큰을 훔친 공격자가 로그아웃으로 할 수 있는 일은 그 토큰 하나를 버리는 것뿐이다. 같은 토큰으로 재발급이 가능하므로 추가 위험이 아니다.
  사용자 전체 폐기였다면 토큰 하나로 모든 기기를 강제 로그아웃시킬 수 있었다

### 결정 5 — 재발급은 액세스 토큰만 주고, 권한은 DB에서 다시 읽는다

- `TokenReissueResponse(accessToken, tokenType, expiresIn)`. 순환 발급 전이라 기존 리프레시 토큰을 계속 쓴다
- `findWithUserByTokenHash()`로 사용자를 함께 가져와 `createAccessToken(user.getId(), user.getRole())`. 권한 회수가 재발급 시점에 반영된다
- 재발급은 본문 값을 JWT로 해석하지 않는다. 해시해서 DB에서 찾을 뿐이라 액세스 토큰은 `not_found`가 된다

| 경로 | 받는 곳 | 검증 수단 |
|---|---|---|
| 보호 API | 헤더 | 서명 (`JwtProvider.parse`) |
| 재발급 | 본문 | DB 조회 (`findWithUserByTokenHash`) |

별도의 "토큰 종류 검사" 코드가 없어서 그 검사를 빠뜨릴 틈도 없다.

### 결정 6 — 오류 코드는 `REFRESH_TOKEN_INVALID`(401) 하나

- 액세스 토큰에서 만료를 따로 둔 이유는 "재로그인으로 복구 가능"이었다. 리프레시 토큰은 없음·만료·폐기 모두 할 일이 재로그인 하나다
- 원인을 나눠 알리면 "이 토큰이 한때 유효했는지", "사용자가 로그아웃했는지"가 드러난다
- 디버깅 비용은 로그로 메운다. `log.warn("[RefreshToken] 재발급 거부 reason={} tokenId={}")`. 원문은 로그에 쓰지 않는다
- 본문 필드 누락·빈 값은 기존 규칙대로 400 `INVALID_INPUT_VALUE`
- RFC 6749 5.2절은 잘못된 리프레시 토큰에 400 `invalid_grant`를 쓰지만, 과제 표와 `LOGIN_FAILED`에 맞춰 401로 했다.
  컨트롤러에서 전역 예외 처리로 나가므로 `WWW-Authenticate`는 붙지 않는다(`LOGIN_FAILED`와 같다)

### 한계 — 로그아웃해도 액세스 토큰은 만료까지 유효

액세스 토큰 검증은 서명만 보고 `refresh_token`을 조회하지 않는다. 테스트로 명시했다(`로그아웃_후에도_액세스_토큰은_만료_전까지_유효하다`).
막으려면 `jti` + 차단 목록 또는 사용자별 무효화 시각이 필요하고, 둘 다 요청마다 저장소 조회를 부른다. 범위 밖이며 현재 완화 수단은 짧은 수명뿐이다.

### 발견 — `@Transactional` 누락을 시나리오 테스트가 잡지 못한다

클래스 기본값이 `@Transactional(readOnly = true)`라 쓰기 메서드에 `@Transactional`이 빠지면 읽기 전용으로 돈다. 임시로 빼고 실측했다.

| 메서드 | 시나리오 테스트 | 테스트 트랜잭션 없이 실행 |
|---|---|---|
| `login()` (INSERT) | 통과 | H2에서 저장됨. `IDENTITY`라 `save()` 시점에 INSERT가 즉시 나간다. MySQL은 읽기 전용 연결에서 거부할 가능성이 크다(미확인) |
| `logout()` (변경 감지 UPDATE) | 통과 | **`revoked_at`이 null로 남는다.** 읽기 전용 트랜잭션은 커밋 전 반영을 하지 않아 폐기가 조용히 유실되고 응답은 200 |

시나리오 테스트는 테스트 트랜잭션 안에서 돌아 서비스의 읽기 전용 트랜잭션이 쓰기 가능한 트랜잭션에 합류하고,
테스트의 `flushAndClear()`가 변경을 대신 반영한다. 다음 세션의 순환 발급(사용 완료 표시 + 새 토큰 저장)은 같은 함정이 있으므로,
테스트 트랜잭션 없이 도는 검증을 최소 하나 둔다.

### 테스트 구조

- `AdminAccountInitializerTest`: 트랜잭션 밖에서 로그인하므로 `refresh_token` 행이 커밋되고, `@AfterAll`의 관리자 삭제가
  `Referential integrity constraint violation`으로 실패했다(커밋 2에서 예상대로 발생). 정리 순서를 "리프레시 토큰 → 관리자"로 바꿨다
- `AuthScenarioTest`: `loginBody()`, `loginForRefreshToken()` 추가. 기존 `login()` 결과는 같다
- 만료 토큰은 만료 시각을 과거로 둔 행을 해시와 함께 직접 저장한다. sleep 없음

| 클래스 | 수 | 내용 |
|---|---|---|
| `RefreshTokenProviderTest` | 5 | 43자 `Base64URL`, 점 없음, 1만 개 중복 없음, SHA-256 결정성·고정값, 만료 시각 |
| `RefreshTokenTest` | 4 | 만료 경계, 폐기, 폐기 시각 유지 |
| `RefreshTokenRepositoryTest` | 2 | 해시로 사용자 포함 조회, 해시 유니크 제약 |
| `RefreshTokenScenarioTest` | 19 | 과제 시나리오 9개 + 로그인 실패 미저장, 기기별 토큰, 만료 액세스 토큰 헤더로 재발급, DB 권한, 거부 응답 동일, 빈 값 400 ×2, 다른 기기 유지, 로그아웃 멱등, 로그아웃 후 액세스 토큰 유효 |

### API

| 메서드 | 경로 | before | after |
|---|---|---|---|
| POST | `/api/auth/login` | `{accessToken, tokenType, expiresIn}` | `+ refreshToken, refreshTokenExpiresIn` (기존 필드 유지) |
| POST | `/api/auth/reissue` | 없음 | **신규**, 공개. `{refreshToken}` → `{accessToken, tokenType, expiresIn}` |
| POST | `/api/auth/logout` | 없음 | **신규**, 공개, 멱등. `{refreshToken}` → 200 |

### README

`## 리프레시 토큰`을 맨 끝에 추가했다(흐름, API, 저장 방식, 구분, 거부 정책, 한계와 완화, 테스트 결과 표).
함께 현행화한 곳: ERD와 테이블 정의(`refresh_token`), 「JWT 인증 흐름 정리 2번」의 "액세스 토큰만 구현", 6번 상황 표,
「인증·인가」의 공개 경로·오류 코드·로컬 설정 표(`REFRESH_TOKEN_VALIDITY`), 세션 4 테스트 개수 문장.

### 확인

`./gradlew test` 260개 통과(기존 230 + 신규 30). 로컬 실행 시 `.env`에 `REFRESH_TOKEN_VALIDITY`가 필요하다.

## 3주차 세션 6: 리프레시 토큰 순환 발급 / 재사용 탐지

도전 심화 과제. 재발급할 때마다 새 리프레시 토큰을 주고 보낸 토큰은 사용 완료 처리한다. 사용 완료된 토큰이 다시 오면
탈취로 보고 그 로그인에서 이어진 토큰 묶음 전체를 폐기한다. 커밋은 순환 발급 / 재사용 탐지 / 로그아웃 묶음 폐기 / 문서 네 개로 나눴다.

### 결정 1 — 컬럼 두 개 추가: `family_id`, `used_at`

세션 5 결정 3대로 이번에 정했다. 로그인마다 행이 따로라 "같은 로그인의 묶음"을 알 방법이 없었고 사용 완료 상태도 없었다.

- `family_id char(36)`: 로그인 때 UUID를 만들고 순환한 토큰이 물려받는다. 첫 토큰 id를 묶음 id로 쓰면 `IDENTITY`라 INSERT 후 다시
  UPDATE해야 해서 택하지 않았다. 부모 토큰 id 컬럼은 두지 않았다. 폐기 단위가 묶음 전체라 체인을 따라갈 일이 없다
- `idx_refresh_token_family` 인덱스: 묶음 폐기의 조회 조건. 인덱스 없는 `UPDATE ... WHERE family_id=?`는 InnoDB가 풀스캔하며
  지나간 행을 전부 잠가 모든 사용자의 재발급을 막는다
- `used_at datetime`: `revoked_at`과 같은 방식. 재사용 로그에 "사용 후 몇 ms 뒤 다시 왔는지"(`elapsedMs`)를 남길 수 있다
- 엔티티 메서드: `rotate(nextHash, now)`가 사용 완료 표시와 자식 생성을 함께 한다. `isUsableAt`은 사용 완료도 포함한다

### 결정 2 — 절대 만료: 새 토큰은 부모의 `expires_at`을 물려받는다

순환할 때마다 만료를 늘리면 훔친 쪽이 계속 순환해 무기한 쓸 수 있다. 물려받으면 세션 5의 "로그인 후 validity가 지나면
재로그인" 동작이 그대로 유지된다. 재발급 응답의 `refreshTokenExpiresIn`은 남은 초다.
부수 효과로 만료된 토큰은 묶음 전체가 이미 죽은 상태라, 만료 토큰에 대해서는 묶음 폐기를 따로 할 필요가 없다.

### 결정 3 — 동시 재발급은 토큰 행 비관적 잠금

| 방식 | 판단 |
|---|---|
| 비관적 잠금 `FOR UPDATE` | **채택.** 상태 판정이 엔티티 메서드에 남는다. 잠금 읽기는 최신 커밋본을 읽어 뒤 요청이 자연스럽게 "사용 완료" 분기로 간다 |
| 조건부 UPDATE (`WHERE used_at IS NULL AND ...`) | 판정 규칙이 JPQL에 한 벌 더 생기고, 0건이면 원인을 알려고 다시 읽어야 한다. 매점 결정 3에서도 같은 이유로 택하지 않았다 |
| 낙관적 잠금 `@Version` | 진 요청이 커밋 때야 실패한다. 토큰을 이미 만든 뒤라 낭비이고 컬럼도 하나 더 필요하다 |

- 매점 재고와 같은 기준: 경합 단위(토큰 하나)와 정확히 맞는 행이 있다. 좌석에서 잠금을 거절한 이유(잠글 행이 없음)는 해당하지 않는다
- 매점의 교훈을 그대로 적용: 잠금 조회에 `JOIN FETCH user`를 넣지 않는다. MySQL `FOR UPDATE`가 users 행까지 잠가 같은 사용자의
  다른 기기 재발급까지 줄을 선다. `findWithUserByTokenHash`는 쓰는 곳이 없어져 지웠고 `findByTokenHashForUpdate`로 바꿨다.
  사용자는 트랜잭션 안에서 LAZY로 읽는다(운영은 `open-in-view: false`)
- 격리 수준만으로 못 막는 이유: MySQL REPEATABLE READ의 일반 SELECT는 스냅샷 읽기라 두 요청 모두 미사용을 보고, 뒤 UPDATE는
  앞 커밋을 기다린 뒤 판정 없이 덮어쓴다(쓰기 충돌에 오류를 내지 않는다). 새 토큰이 둘 생겨 묶음이 갈라진다.
  SERIALIZABLE은 S 잠금끼리 교착 → 하나가 강제 롤백되는 방식이라 교착 오류에 기대고 전역으로 느려진다

### 결정 4 — 재발급 확인 순서: 사용 완료 → 폐기 → 만료

처음 계획은 폐기 → 만료 → 사용 완료였다. 작성자가 짚은 문제: 동시 N건(N ≥ 3)에서 두 번째 요청이 묶음을 폐기하면 원래 토큰도
폐기 상태가 되어, 세 번째부터는 폐기 확인에 먼저 걸려 일반 거부가 된다. 사용 완료 토큰이 다시 온 것은 묶음이 이미 폐기됐어도
재사용 신호로 본다. 사용된 적 없이 폐기된 토큰(로그아웃한 토큰, 탐지로 함께 폐기된 최신 토큰)은 여전히 `REFRESH_TOKEN_INVALID`.
실험으로 확인했다(아래 발견 3).

### 결정 5 — 재사용 탐지: 묶음 전체 폐기 + 구분된 오류 코드 + 로그, `noRollbackFor`

- 누가 공격자인지 모른다. 들어온 토큰만 막으면 먼저 순환한 공격자가 남고 정상 사용자만 쫓겨날 수 있다. 묶음 전체를 끊으면
  공격자는 반드시 밀려나고, 다시 들어올 수 있는 쪽은 비밀번호를 가진 정상 사용자뿐이다. 다른 로그인의 묶음은 건드리지 않는다
- `REFRESH_TOKEN_REUSE_DETECTED`(401). 세션 5 결정 6의 기준("복구할 행동이 다르면 코드를 나눈다")에 맞다. 재로그인에 더해
  탈취 가능성을 알리는 할 일이 있다
- 로그: `[RefreshToken] 재사용 탐지 userId familyId tokenId usedAt elapsedMs revoked`
- `revokeFamily`는 `revoked_at IS NULL`인 행만 갱신한다(처음 폐기 시각 유지). 벌크 UPDATE는 감사 리스너를 거치지 않아 `updatedAt`을
  직접 쓰고, `flushAutomatically`/`clearAutomatically`로 영속성 컨텍스트의 옛 상태를 비운다
- **예외를 던지면 폐기가 롤백되는 문제**: `RefreshTokenService.reissue`에 `noRollbackFor = CustomException.class`.
  이 메서드에서 쓰기 뒤에 예외를 던지는 곳은 재사용 분기뿐이다
  - `REQUIRES_NEW`로 폐기만 분리하는 안은 스스로 멈춘다. 바깥 트랜잭션이 쥔 토큰 행 X 잠금을 안쪽 UPDATE가 기다리고, 바깥은 같은
    스레드에서 안쪽이 끝나길 기다린다. 한쪽 대기가 DB 밖(호출 스택)에 있어 InnoDB 교착 탐지에 걸리지 않고, 매번 잠금 대기 한도(3초)
    뒤 실패하며 그동안 커넥션 두 개를 쥔다
  - 결과 객체를 돌려주고 트랜잭션 밖에서 예외를 던지는 안은 정확하지만 결과 타입과 빈이 늘어 이 규모에서는 과하다

### 결정 6 — 유예 시간 없음

같은 토큰이 짧은 간격으로 두 번 오면 재시도든 탈취든 재사용으로 처리한다.

- 첫 응답이 유실된 재시도를 구제하려면 유효한 토큰을 다시 줘야 한다. 같은 자식을 주려면 원문 저장(해시만 저장 원칙 위반),
  자식을 하나 더 주면 묶음이 갈라져 유예 시간 안에 들어온 공격자도 살아 있는 갈래를 얻는다
- "유예 시간 안에는 탐지 없이 일반 거부"는 공격자가 먼저 순환하고 정상 사용자가 몇 초 뒤 따라오는 경우 정상 사용자만 튕기고
  탐지가 일어나지 않는다. 탈취 요청을 즉시 재전송하는 공격이 정확히 이 구간이다
- 오탐의 대가는 재로그인 한 번, 놓쳤을 때의 대가는 최대 14일의 탈취. 대신 README에 "재발급 요청은 하나로 묶어 보낸다"를 적었다
- 그래서 동시 N건의 기대 결과는 "1건 성공 + N-1건 재사용 탐지 + 승자의 새 토큰도 폐기"다

### 결정 7 — 잠금 대기 초과는 트랜잭션 바깥에서 401로 바꾼다

- 409로 재시도를 안내하면 그 재시도가 사용 완료 토큰이 되어 재사용으로 탐지된다. 그래서 401 `REFRESH_TOKEN_INVALID`,
  `reason=lock_timeout`. 판정을 못 했으므로 묶음은 폐기하지 않는다
- 트랜잭션 경계를 `RefreshTokenService`로 떼고, `AuthService.reissue()`가 그 호출을 감싸 `ConcurrencyFailureException`을 바꾼다.
  같은 클래스 안 호출은 프록시를 안 거쳐 트랜잭션이 안 걸리므로 빈을 나눴다. 로그인의 발급도 같은 서비스로 옮겼다
- `AuthService.reissue()`는 `propagation = SUPPORTS`. 스스로 트랜잭션을 열지 않되, 시나리오 테스트처럼 바깥 트랜잭션이 있으면
  합류해 미커밋 데이터를 본다. `NOT_SUPPORTED`는 테스트 트랜잭션을 끊어 테스트가 넣은 데이터가 안 보인다(좌석 세션의 `REQUIRES_NEW`와 같은 문제)

### 결정 8 — 로그아웃은 묶음 전체 폐기 (세션 5 결정 4 변경)

작성자 결정. 공격자가 먼저 재발급하면 정상 사용자가 가진 것은 사용 완료 토큰이다. 그 행만 폐기하면 공격자의 최신 토큰이 만료까지 산다.

- 사용 완료 토큰으로 로그아웃해도 200. 세션 5의 "로그아웃이 유효성 확인 창구가 되지 않는다"를 유지하고 탐지는 경고 로그로만 남긴다
- 없는 토큰·이미 폐기된 토큰도 200, 처음 폐기 시각 유지(같은 `revokeFamily` 쿼리)
- 토큰을 훔친 공격자가 로그아웃으로 할 수 있는 일은 그 로그인 하나를 끊는 것. 재사용 탐지로도 이미 가능한 일이라 추가 위험이 아니다
- 세션 5 로그아웃 테스트 5개는 기대 결과가 바뀌지 않았다(묶음에 행이 하나뿐인 상황이라 결과가 같다)
- 폐기가 모두 벌크 쿼리가 되어 `RefreshToken.revoke()`는 쓰는 곳이 없어졌다. 멱등 규칙(처음 폐기 시각 유지)이 엔티티와 쿼리 두 곳에
  있으면 하나만 고쳐질 때 어긋나므로 지웠다(별도 refactor 커밋). 함께 지운 엔티티 테스트 2개의 규칙은 남은 테스트가 검증한다
  - 폐기된 토큰 거부: `로그아웃한_리프레시_토큰으로_재발급하면_401_…`, `재사용_탐지_후_같은_묶음의_최신_토큰은_401`
  - 처음 폐기 시각 유지: 이제 `revokeFamily`의 `revoked_at IS NULL` 조건. `로그아웃은_멱등이다`, `묶음이_폐기된_뒤에도_사용_완료_토큰은_재사용으로_탐지한다`

### 발견 1 — 잠금 대기 초과를 트랜잭션 안에서 잡으면 500이 되는가: 조건부

작성자가 "JPA 구현체가 이미 롤백 전용으로 표시했을 수 있다"고 짚었다. 실측(끝난 뒤 원복):

| 실험 | 결과 |
|---|---|
| 트랜잭션 안에서 잡아 `CustomException` + `noRollbackFor` | **401**, 문제없음 |
| 위 + 잠금 조회 저장소 메서드에 `@Transactional` | **500** `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only` |

- 지금 401인 이유 두 가지가 맞아떨어졌다
  - Spring Data는 인터페이스에 선언한 `@Query` 메서드에 기본 트랜잭션을 걸지 않는다. 예외가 그 메서드를 빠져나가도 참여 트랜잭션
    실패로 롤백 전용이 표시되지 않는다
  - Hibernate 7.4.5는 H2·MySQL 방언 모두 잠금 대기 초과를 `LockTimeoutException`으로 분류하고, 이 예외는 롤백 전용 표시를 하지
    않는다(바이트코드로 확인: `ExceptionConverterImpl.wrapLockException` → `rollbackIfNecessary`의 비롤백 예외)
- 둘 중 하나만 바뀌어도 500이다(저장소 메서드에 트랜잭션이 붙거나, DB가 교착으로 보고해 `LockAcquisitionException`이 되는 경우).
  그래서 결정 7의 구조를 유지했다
- MySQL로는 실측하지 않았다. 테스트 설정이 `create-drop`이라 개발 DB에서 돌리면 테이블이 지워진다

### 발견 2 — `noRollbackFor`가 빠져도 시나리오 테스트는 통과한다

세션 5 발견과 같은 함정. 임시로 빼고 돌렸다.

| 테스트 | 결과 |
|---|---|
| `RefreshTokenScenarioTest` (테스트 트랜잭션 안) | 25개 **전부 통과**. 롤백될 폐기도 같은 트랜잭션에서는 보인다 |
| `RefreshTokenConcurrencyTest` (트랜잭션 없음) | **2개 실패**: 동시 재발급, 재사용 탐지 커밋 확인. 새 트랜잭션에서 읽으니 묶음이 폐기되지 않았다 |

### 발견 3 — 테스트가 실제로 버그를 잡는지 확인한 나머지 실험

| 일부러 넣은 버그 | 결과 |
|---|---|
| 잠금 조회에서 `@Lock` 제거 | 동시 10건 중 **7건 성공** → 동시 재발급 테스트 실패 |
| 확인 순서를 폐기 → 사용 완료로 | 동시 10건 중 두 번째만 재사용 탐지, 나머지 8건 `reason=revoked` → 동시 재발급 테스트와 `묶음이_폐기된_뒤에도_사용_완료_토큰은_재사용으로_탐지한다` 실패 |

### 테스트 구조

- `RefreshTokenConcurrencyTest`(신규): `AuthScenarioTest`를 상속하되 `@Transactional(propagation = NOT_SUPPORTED)`로 테스트 트랜잭션을
  끈다. 가입·로그인 헬퍼를 그대로 쓰고 실제 필터 체인으로 요청한다. 동시 요청은 `ExecutorService` + `CountDownLatch`(준비 래치 +
  출발 래치). `@AfterEach`에서 이 클래스가 만든 사용자(`rtconc*`)의 토큰 → 사용자 순으로 지운다
- 잠금 대기 초과 재현: 다른 스레드가 `TransactionTemplate` 안에서 같은 행을 `findByTokenHashForUpdate`로 잡고 래치로 붙잡아 둔다.
  H2 기본 잠금 대기 한도는 약 2초(실측 2.8초짜리 테스트). 이후 토큰이 사용되지 않았고 잠금이 풀리면 재발급 200인지도 본다
- 시나리오 테스트의 `storeRefreshToken`은 `familyId`를 넣는다

| 클래스 | 수 | 변경 |
|---|---|---|
| `RefreshTokenTest` | 4 | +2 (순환 시 사용 완료, 새 토큰의 묶음·만료 상속), −2 (`revoke()` 삭제로 폐기·폐기 시각 유지) |
| `RefreshTokenRepositoryTest` | 2 | 사용자 포함 조회 → 잠금 조회로 교체 |
| `RefreshTokenScenarioTest` | 28 | +9 (순환 2, 재사용 탐지 4, 로그아웃 묶음 폐기 3), 재발급 응답 형식 테스트 수정 |
| `RefreshTokenConcurrencyTest` | 4 | 신규 (동시 10건, 재사용 폐기 커밋, 로그아웃 폐기 커밋, 잠금 대기 초과) |

### API

| 메서드 | 경로 | before | after |
|---|---|---|---|
| POST | `/api/auth/reissue` | `{accessToken, tokenType, expiresIn}` | `+ refreshToken, refreshTokenExpiresIn`(남은 초). 보낸 토큰은 사용 완료. 재사용이면 401 `REFRESH_TOKEN_REUSE_DETECTED` |
| POST | `/api/auth/logout` | 보낸 토큰 한 행 폐기 | 보낸 토큰이 속한 묶음 전체 폐기. 응답은 그대로 200 |

### README

`## 리프레시 토큰`을 갱신했다: 흐름, API 표, 「클라이언트가 지켜야 할 것」(새 토큰으로 교체, 재사용 시 재로그인, 절대 만료,
재발급 요청을 하나로 묶어 보내기), 저장 방식(새 컬럼), 「순환 발급과 재사용 탐지」(처리 순서, 탈취 시 사용 가능 기간 비교,
묶음 전체 폐기 이유, 로그아웃, 로그), 「동시 요청 처리 방식과 한계」(잠금 순서, 격리 수준, 유예 시간 없음), 거부 정책, 테스트 결과 표와 실험 표.
함께 현행화한 곳: ERD와 테이블 정의(`family_id`, `used_at`, 인덱스), 「JWT 인증 흐름 정리 2번」, 6번 상황 표, 「인증·인가」 오류 코드 표.

### 확인

`./gradlew test` 273개 통과(세션 5의 260 + 신규 15 − `revoke()` 테스트 2). 테이블은 `ddl-auto: create`라 이관 작업이 없다.

---

## 3주차 PR 리뷰 반영

리뷰 한 건을 커밋 하나로 반영했다. 코드 변경이 없는 리뷰(권한 회수가 액세스 토큰 만료 전까지 반영되지 않는 문제)는 리뷰 답변으로만 정리했다.

### 회원가입 무결성 위반 로그 (`AuthService.signup`)

- `saveAndFlush`의 `DataIntegrityViolationException`을 `DUPLICATE_LOGIN_ID`로 바꾸기 전에 `getMostSpecificCause()` 메시지를 `warn`으로 남긴다
- 지금은 users의 unique 제약이 `login_id`뿐이라 응답은 맞다. 제약이 추가되면 다른 위반도 같은 응답으로 나가는데, 그때 원인을 로그로 찾을 수 있게 하는 것이 목적이다
- 응답은 바꾸지 않았다. 바꾸려면 제약 이름으로 분기해야 하는데, 지금은 분기할 대상이 없다

### 서명키 설정 오류 메시지 (`JwtProvider` 생성자)

- 잘못된 키면 서버가 뜨지 못하는 동작(fail-fast)은 전부터 있었다. 문제는 메시지였다. Base64가 아니면 `DecodingException: Illegal base64 character`,
  짧으면 `WeakKeyException`만 나오고 어느 설정이 틀렸는지(`jwt.secret` / `JWT_SECRET`)가 드러나지 않았다
- `DecodingException | WeakKeyException`을 잡아 설정 이름과 생성 방법을 담은 `IllegalStateException`으로 다시 던진다. 원래 예외는 cause로 남긴다
- `CustomException`을 쓰지 않은 이유: 기동 시점이라 응답할 HTTP 요청이 없고, `ErrorCode`의 상태 코드는 의미가 없다.
  CLAUDE.md의 "IllegalStateException 직접 사용 금지"는 요청 처리 중 비즈니스 예외에 대한 규칙으로 본다
- 키 값은 메시지에 넣지 않는다. 테스트가 메시지에 입력값이 없는지도 확인한다
- 테스트: 짧은 키 테스트의 기대 예외 변경(+cause 확인), Base64가 아닌 키 테스트 추가

### 리프레시 토큰 길이 검사 (`RefreshTokenService.reissue`, `revokeFamilyOf`)

- 문제: 입력 길이 제한이 없어 수 MB 문자열도 SHA-256 전체를 계산하고 잠금 조회까지 갔다. 요청 하나에 드는 일의 양을 보내는 쪽이 정한다
- 발급한 토큰은 항상 43자(`RefreshTokenProvider.TOKEN_LENGTH = (32바이트 × 8 + 5) / 6`)다. 최대 길이만이 아니라 길이가 다르면 모두 해시 전에 거른다.
  짧은 위조값도 발급한 토큰일 수 없어서 DB를 볼 이유가 없다
  - 재발급: 401 `REFRESH_TOKEN_INVALID`, 로그 `reason=length_mismatch`
  - 로그아웃: 아무것도 하지 않고 200. 없는 토큰과 같은 정책이다
- DTO `@Size`(400)를 쓰지 않은 이유 (작성자 결정)
  - 액세스 토큰을 본문에 넣은 경우가 401에서 400으로 바뀌어 "거부 응답은 원인과 관계없이 같다"(세션 5 결정 6)가 깨진다
  - `GlobalExceptionHandler.handleValid`가 rejectedValue를 응답에 담아, 큰 입력이 400 응답으로 그대로 돌아온다
- 한계: 검사는 JSON을 다 읽은 뒤에 한다. 본문 크기 자체는 앞단 프록시(`client_max_body_size` 등)에서 막아야 한다
- 테스트: 10,000자 토큰으로 재발급하면 쿼리 0건, 401, 다른 거부와 본문 동일, 본문에 입력값 없음. 로그아웃은 쿼리 0건, 200.
  `hasIssuedLength`를 항상 `true`로 바꾸면 두 테스트가 쿼리 1건으로 실패하는 것을 확인했다(실험 후 원복)
