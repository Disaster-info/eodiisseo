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
루트 `.env.example`은 변수 목록이며 Spring Boot가 `.env`를 자동으로 읽지는 않습니다.
DB URL은 PostgreSQL JDBC URL로 설정하고, 대상 DB에는 PostGIS 확장이 준비되어 있어야 합니다.
PostgreSQL JDBC 드라이버와 Hibernate Spatial을 포함하며 테이블이나 확장을 자동 생성하지 않습니다.

## 패키지 경계

하나의 Spring Boot 애플리케이션 안에서 기능별 패키지로 모듈을 구분합니다.

- `auth`, `disaster`: 기능 내부에 controller/service/repository/entity/dto를 배치합니다.
- `disaster.mapper`: 정규화 과정의 매핑을 위한 인터페이스 골격입니다.
- `collection`: 외부 데이터 수집, 수집 상태와 스케줄링을 담당합니다. 현재 자동 실행은 등록하지 않습니다.
- `disaster`: 정규화, 중복판정, 재난 통합과 상태 관리를 담당합니다.
- `integration`: 외부 연동별 Client와 외부 응답 DTO를 분리합니다.
- `common`: config/exception/response/security/util의 공통 기능 자리만 마련합니다.
- 나머지 기능 및 `ai.summary/chat/resource`는 최소 클래스 골격입니다.

Controller에 URL, 요청/응답 규격을 정의하지 않았습니다.
Entity는 필드와 JPA 매핑이 없는 일반 클래스이며, Repository는 저장 동작 없는 인터페이스입니다.
식별자 및 스키마가 확정된 뒤 JPA Entity와 Repository 계약을 추가해야 합니다.
DTO, 응답 포맷, 보안 정책, 외부 API 계약과 비즈니스 로직도 아직 구현하지 않았습니다.
외부 응답 DTO를 Entity로 사용하거나 Entity를 Controller 응답으로 반환하지 않습니다.
