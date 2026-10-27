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
Service는 기능 패키지 바로 아래(`shelter/ShelterService.java`)에 두고, 나머지는 하위 패키지로 나눕니다 (팀 공통 구조).
URL·필드 이름·상태코드는 **API 명세서 5.2.1**(팀 공통)을 따릅니다.

```
com.disasterinfo.eodiisseo
├─ <기능>/                     shelter, route, dashboard, notification, ai.chat …
│  ├─ XxxService.java              비즈니스 로직·트랜잭션 (기능 패키지 바로 아래)
│  ├─ controller/  XxxController   HTTP 입출력만. 요청 DTO 검증(@Valid) 후 Service 호출, 응답 DTO를 그대로 반환
│  ├─ dto/         XxxRequest / XxxResponse   API 요청·응답 규격 (record, 명세서 필드 이름 그대로)
│  ├─ entity/      Xxx             저장 모델 (JPA Entity)
│  ├─ repository/  XxxRepository   데이터 접근 (Spring Data JPA)
│  └─ port/ · adapter/            (필요 시) 다른 기능·외부 의존을 인터페이스로 분리 — dashboard, ai 참고
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
- 다른 기능의 데이터가 필요하면 그 기능의 **Service**를 호출합니다. 다른 기능의 Repository를 직접 쓰지 않습니다. 예: `route.RouteService`는 대피소를 `shelter.ShelterService.getShelter()`로 가져옵니다.
- Entity는 Controller 응답으로 반환하지 않고, 외부 응답 DTO(`integration/*`)를 Entity나 API 응답으로 그대로 쓰지 않습니다. 변환은 Service가 합니다.
- 저장할 데이터가 없는 기능은 Repository를 두지 않습니다. 예: `route`는 경로를 요청 시점에 계산하므로 `RouteService`와 controller/dto만 있습니다.
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
- `dashboard`, `notification`, `ai.chat`, `ai.summary`: 팀원 브랜치(`feature/ai-dashboard-noti`)에서 구현 중.
- 나머지 기능(area, checklist, group, location, ops, weather, ai.resource)은 `XxxService` 골격만 있습니다.

외부 응답 DTO를 Entity로 사용하거나 Entity를 Controller 응답으로 반환하지 않습니다.

## 로컬 PostgreSQL과 전체 DDL

저장소 루트에서 다음 명령을 실행합니다. Windows 사용자 경로에 PostgreSQL 17.11과 PostGIS 3.6.2를 설치하며 시스템 서비스는 등록하지 않습니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/database/Install-LocalDatabase.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/database/Start-LocalDatabase.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/database/Start-LocalDatabase.ps1 -Audit
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/database/Test-LocalDatabase.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/database/Run-LocalBackend.ps1
```

- 개발 DB: `127.0.0.1:55432/eodiisseo_local`, 계정 `eodiisseo_app`. 비밀번호와 JWT 키는 최초 실행 시 생성해 Git에서 제외되는 루트 `.env`에 저장합니다. 기존 `.env`는 덮어쓰지 않습니다.
- 테스트 클러스터: `127.0.0.1:55433`. 별도 데이터 경로이며 `eodiisseo_audit`는 이 클러스터의 loopback에서만 비밀번호 없이 접속합니다. 개발 DB의 사용자 데이터를 테스트에 사용하지 않습니다.
- 설치 경로: `%LOCALAPPDATA%/eodiisseo/postgresql17`. 테스트가 만드는 `eodiisseo_verify_*` DB와 `audit_*` 스키마는 해당 테스트가 정리합니다.
- 종료: `Stop-LocalDatabase.ps1`, 테스트 DB는 같은 명령에 `-Audit`를 추가합니다. 데이터 디렉터리는 삭제하지 않습니다. Windows 로그인 후 필요할 때 시작 스크립트를 다시 실행합니다.

**새 DB의 기준은 `db/migration`의 Flyway SQL입니다.** ERD 4.2.1의 36개 테이블·공간 제약·트리거·설명과 인증 제한용 보조 테이블을 생성합니다. 최초 세 번의 migration은 전체 스키마, 요청 제한, 공식자료 기반 체크리스트·제공처 데이터입니다. PostGIS 확장은 시작 스크립트가 관리자 계정으로 미리 설치하며 앱 계정은 superuser가 아닙니다. 서버 시작 시 JPA `validate`로 실제 테이블과 엔터티도 확인합니다.

`db/assigned/V2026101601`~`1605`는 기존 이력과 기존 격리 테스트를 위해 보존하지만 기본 실행 경로에서는 제외합니다. 독립 DDL과 새 마이그레이션을 같은 DB에 중복 실행하지 마세요. 기존 assigned 이력이 있는 DB를 자동 전환하거나 baseline/repair/clean하지 않습니다. 그런 DB는 별도의 현황 조사와 전환 migration이 필요합니다.

`Test-LocalDatabase.ps1`는 기존 타 기능 audit 테스트가 직접 만드는 최소 스키마에서는 Flyway를 끄고, `CanonicalDatabaseIntegrationTest`에서는 완전히 새 DB에 실제 production migrations를 명시적으로 실행합니다. 따라서 최소 fixture와 실제 전체 스키마 검증이 섞이지 않습니다. Docker 전용 테스트와 실제 Ollama 호출 테스트는 각각 해당 환경이 있어야 실행됩니다.

API 키는 `.env`에 `KMA_SERVICE_KEY` 또는 `DATA_GO_KR_SERVICE_KEY`, `GOOGLE_CLIENT_IDS`, `KAKAO_APP_ID` 등으로 별도 설정합니다. 키·토큰·DB 비밀번호는 커밋하지 않습니다. 실제 사용자 로그인은 앱에서 얻은 Google ID token 또는 Kakao access token으로 확인해야 합니다.

체크리스트 seed는 공식 지진·호우·산불 행동요령을 앱용으로 요약하고 원문 URL·확인시각을 함께 저장합니다. 공식 행정경계·재난·수집 성공 이력·운영자 계정은 임의로 만들지 않습니다. 관리자의 수집 접수는 수집 담당 `ShelterRefreshGateway` 구현 연결 전까지 503입니다. 대피소 저장소가 아직 메모리 구현이라는 점도 DDL 작성만으로 변경되지 않습니다.
