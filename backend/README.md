# eodiisseo Backend

Java 21 / Spring Boot 4.0.8 / Gradle 8.14.4 (Groovy DSL)

## 빌드

저장소 루트에서 `cd backend` 후 실행합니다.

- Linux/macOS: `./gradlew build`
- Windows PowerShell: `.\gradlew.bat build`

테스트는 DB 자동 설정을 제외한 test 프로필로 애플리케이션 컨텍스트를 검증합니다.
PostgreSQL 접속과 PostGIS 공간 쿼리는 이 테스트의 검증 대상이 아닙니다.

## 실행 환경

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`를 프로세스 환경 변수로 설정한 후
`./gradlew bootRun` (Windows: `.\gradlew.bat bootRun`)을 실행합니다.
외부 API를 쓰는 기능은 해당 키(`TMAP_APP_KEY`, `SAFETYDATA_EARTHQUAKE_SHELTER_KEY` 등)도 환경 변수 또는 루트 `.env`로 설정합니다.
루트 `.env.example`은 변수 목록이며 Spring Boot가 `.env`를 자동으로 읽지는 않습니다.
DB URL은 PostgreSQL JDBC URL로 설정하고, 대상 DB에는 PostGIS 확장이 준비되어 있어야 합니다.
PostgreSQL JDBC 드라이버와 Hibernate Spatial을 포함하며 테이블이나 확장을 자동 생성하지 않습니다.

## 계층 구조

기능(도메인)별 패키지 안에 Controller → Service → Repository 계층을 두고, 계층 간 데이터는 DTO로 주고받습니다.
URL·필드 이름·상태코드는 **API 명세서 5.2.1**(팀 공통)을 따릅니다.

```
com.disasterinfo.eodiisseo
├─ <기능>/                     auth, disaster, route, shelter, group …
│  ├─ controller/  XxxController   HTTP 입출력만. 요청 DTO 검증(@Valid) 후 Service 호출, 응답 DTO를 그대로 반환
│  ├─ service/     XxxService      비즈니스 로직·트랜잭션. Repository와 integration Client를 조합
│  ├─ repository/  XxxRepository   데이터 보관 (DB 연동 후 Spring Data JPA)
│  ├─ entity/      Xxx             저장 모델 (DB 연동 후 JPA Entity)
│  └─ dto/         XxxRequest / XxxResponse   API 요청·응답 규격 (record, 명세서 필드 이름 그대로)
├─ integration/<제공처>/        외부 API Client + 외부 규격 DTO (TmapClient, SafetydataClient …)
├─ collection/                 외부 재난 데이터 수집·스케줄링
└─ common/
   ├─ response/                ErrorBody {code, message, traceId}, GeoPoint {latitude, longitude}
   ├─ exception/               ErrorCode, BusinessException, ExternalApiErrors, GlobalExceptionHandler
   ├─ config/                  TraceIdFilter(요청 추적 ID), WebConfig(CORS)
   └─ util/                    GeoUtils(직선거리), UuidV5(원본 키 → 고정 UUID)
```

### 의존 규칙

- 호출 방향은 `Controller → Service → Repository / integration Client` 한 방향입니다. 역방향·계층 건너뛰기(Controller → Repository)는 하지 않습니다.
- 다른 기능의 데이터가 필요하면 그 기능의 **Service**를 호출합니다. 다른 기능의 Repository를 직접 쓰지 않습니다. 예: `RouteService`는 대피소를 `ShelterService.getShelter()`로 가져옵니다.
- Entity는 Controller 응답으로 반환하지 않고, 외부 응답 DTO(`integration/*`)를 Entity나 API 응답으로 그대로 쓰지 않습니다. 변환은 Service가 합니다.
- 저장할 데이터가 없는 기능은 Repository를 두지 않습니다. 예: `route`는 경로를 요청 시점에 계산하므로 controller/service/dto만 있습니다.
- 계층 패키지(repository, entity …)는 실제 코드가 생길 때 만듭니다.

### 응답과 오류 (API 명세서 "공통 타입과 상태 계약")

- 성공: Controller가 응답 DTO를 **그대로** 반환합니다 (감싸지 않음). 예: `{"baseLocation": {...}, "searchedRadiusKm": 1, "shelters": [...]}`
- 실패: Service·Client에서 `throw new BusinessException(ErrorCode.SHELTER_NOT_FOUND)` → GlobalExceptionHandler가 ErrorCode의 HTTP 상태로 변환
  → `{"code": "SHELTER_NOT_FOUND", "message": "대피소를 찾을 수 없습니다.", "traceId": "…"}`
- `traceId`는 요청마다 `TraceIdFilter`가 만들며, 응답 헤더 `X-Trace-Id`로도 내려갑니다.
- 내부 ID는 UUID 문자열, 좌표 필드는 `latitude`/`longitude`입니다.

| 상황 | HTTP | code |
|---|---|---|
| 검증 실패·파라미터 누락·UUID 형식 오류 | 400 | `INVALID_INPUT` |
| 없는 URL | 404 | `NOT_FOUND` |
| 없는 대피소 | 404 | `SHELTER_NOT_FOUND` |
| 외부 API 오류 응답·인증 실패·키 미설정·파싱 실패 | 502 | `EXTERNAL_API_ERROR` / `EXTERNAL_API_NOT_CONFIGURED` |
| 유효한 경로선 없음 | 502 | `ROUTE_NOT_FOUND` |
| 외부 API 시간 초과 | 504 | `EXTERNAL_API_TIMEOUT` |
| 예상하지 못한 오류 | 500 | `INTERNAL_ERROR` |

기능별 오류는 `ErrorCode`에 접두어(`ROUTE_`, `SHELTER_` …)를 붙여 추가합니다.
명세의 429 `RATE_LIMITED`와 인증(Bearer)은 로그인(AUTH) 구현 후 적용합니다.

### 구현된 API

| API ID | Method·URL | Controller → Service → 외부/저장소 |
|---|---|---|
| API-SHELTER-001 | `GET /api/v1/shelters/nearby?latitude&longitude` | `ShelterController` → `ShelterService` → `SafetydataClient`(SD-001), `ShelterRepository` |
| API-SHELTER-002 | `GET /api/v1/shelters/{shelterId}` | `ShelterController` → `ShelterService` → `ShelterRepository` |
| API-ROUTE-001 | `GET /api/v1/routes/shelters/{shelterId}?originLatitude&originLongitude` | `RouteController` → `RouteService` → `ShelterService`, `TmapClient` |

새 기능을 만들 때 이 흐름을 따라 작성합니다. 명세 계약(URL·필드·오류 형식)은 `ApiContractTest`로 검증합니다.

**DB 연동 전 임시 구조**: 대피소는 요청 시 공공데이터(SD-001)를 조회해 `InMemoryShelterRepository`(서버 메모리)에 보관합니다.
shelterId는 원본 키로 만든 UUID v5라 재시작해도 같은 대피소는 같은 ID입니다. 다만 재시작 직후에는 주변 조회를 한 번 해야 상세·경로 조회가 됩니다(그 전엔 404).
DB가 준비되면 `ShelterRepository`를 JPA 구현으로 바꾸고, 공공데이터 조회는 수집(collection)으로 옮깁니다.

**명세에 없어 등록 검토가 필요한 것**: ROUTE-001 응답의 `guides`(TMAP 안내지점·문구 — 처리 내용 ⑨ "추가 이동정보"에 해당).

## 패키지별 현황

- `auth`, `disaster`: controller/service/repository/entity/dto 골격. Entity·Repository는 스키마 확정 후 JPA 매핑을 추가합니다.
- `disaster.mapper`: 정규화 과정의 매핑을 위한 인터페이스 골격입니다.
- `collection`: 외부 데이터 수집, 수집 상태와 스케줄링을 담당합니다. 현재 자동 실행은 등록하지 않습니다.
- `disaster`: 정규화, 중복판정, 재난 통합과 상태 관리를 담당합니다.
- `shelter`, `route`: 위 "구현된 API" 참고.
- 나머지 기능(area, checklist, dashboard, group, location, notification, ops, weather, ai.*)은 `service/` 골격만 있습니다.

외부 응답 DTO를 Entity로 사용하거나 Entity를 Controller 응답으로 반환하지 않습니다.
