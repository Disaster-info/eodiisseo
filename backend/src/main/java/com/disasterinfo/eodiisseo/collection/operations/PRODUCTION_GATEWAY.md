# Production Collection Gateway 계약 (4C-1)

## 기본 비활성
선택적 Flyway location `classpath:db/collection-production`의 V2026111301을 사용한다. 1001~1006과 owned-runtime이 선행한다. Migration은 기존 이력을 수정하지 않으며, 역할·credential·설치 행·운영 승인을 생성하지 않는다. `PreparedCollectionRuntime.collect`는 계속 DISABLED이고 Scheduler/공개 arming API/Worker 교체를 추가하지 않는다.

## 신뢰 경계
Worker DB 계정은 공용/원문/도메인 직접 DML과 private context 읽기 권한이 없어야 한다. 별도 Gateway 서비스 계정과 DataSource가 짧은 DB-only 트랜잭션만 실행한다. 이 credential은 Worker 프로세스에 공급하지 않는다.
`ProductionCollectionWriteGateway.write`의 Supplier는 검수된 내부 Repository/Service 호출 전용이다. HTTP endpoint/RPC로 SQL이나 callback을 수신하는 기능이 아니다. 외부 HTTP/모델/FCM은 이 callback에 넣지 않는다.

SECURITY DEFINER 함수는 고정된 search_path와 정적 SQL을 사용하며 동적 SQL 입력을 받지 않는다. 실제 쓰기는 기존 JPA/JDBC Repository를 재사용하되 각 행의 Production guard가 protected context·source·run·target·Fence·세대·유효기간을 확인한다. context는 pg_current_xact_id와 pg_backend_pid에 묶이며 GUC만 설정해서 만들 수 없다. 최종 flush/close와 deferred commit guard도 검증한다.
RLS는 연결별 고정 Worker 역할만으로 실행/세대/Fence 및 JPA 다중 테이블 트랜잭션을 표현하기 어려우므로 이번 구현에서는 적용하지 않았다. ACL과 보호 context·행 trigger를 함께 사용한다. FORCE RLS/owner 우회까지 보장하는 것으로 보고하지 않는다.

DB owner/superuser는 trigger/함수를 변경할 수 있다. 이 계정은 실제 Worker/Gateway/OPS에 사용할 수 없다. 계정 분리 자체는 배포 담당자가 검토·적용해야 한다.

## 별도 검토할 역할/GRANT 계획 (운영 실행 금지)
- Migration 계정: 스키마 변경 전용. 앱에 credential을 주지 않는다.
- Definer 소유자: NOLOGIN·NOINHERIT·NOSUPERUSER, 앱 역할에 membership 부여 금지. 검토한 고정 함수만 소유한다.
- Gateway: 별도 LOGIN·NOINHERIT·NOSUPERUSER, DB/schema CREATE·TEMP·TRIGGER·TRUNCATE·소유권 금지. 검수된 기존 Collection/Projection 테이블의 필요한 SELECT/DML만 허용하며 행 trigger를 우회할 수 없다. private 테이블 직접 DML은 금지한다.
- Worker: LOGIN·NOINHERIT, 직접 DML/원문·capability SELECT 금지. 필요하면 status 함수의 EXECUTE와 metadata view 읽기만 승인한다.
- OPS: 제어 함수 freeze/seal/issue_worker/revoke_worker/resolve_held의 EXECUTE만 검토한다. 기존 인증 서비스가 사용하는 row-lock/권한 구성과 통합 검토가 별도로 필요하다. 포괄적인 auth 원문·token SELECT를 주지 않는다.

함수 소유권은 위 Definer로 이전하고 PUBLIC EXECUTE를 회수한다. 필요한 함수만 각 역할에 GRANT하며 앱 역할에 Definer SET ROLE 경로를 만들지 않는다. PostgreSQL의 PUBLIC TEMP/CREATE 권한을 회수해야 한다면 공유 DB 사용자 영향부터 확인한다. 이 작업은 Migration에서 자동 실행하지 않는다.
`collection_production.installation`의 worker_role/gateway_role/ops_role/legacy_roles는 운영 승인된 정확한 역할명을 사용한다. legacy_roles는 기존 모든 producer 계정의 완전한 목록이어야 한다. 다른 팀의 독립 domain consumer 권한은 별도 유지하며, 미등록 producer나 superuser를 barrier가 통제할 수 있다고 주장하지 않는다. 모든 Collection 실행/원문 테이블은 설치 후 context 없이 변경할 수 없다.

## 전환 상태와 증거
UNCONFIGURED → FROZEN → SEALED. freeze와 재전환은 세대를 증가시킨다. 설치 행이 없으면 기존 경로는 유지된다. 설치 후 FROZEN은 legacy 쓰기를 막는다. 따라서 운영에서는 Fleet 정지/재시작 금지·진행 요청 조사와 Resolution을 먼저 계획하고, 검토한 변경 창에서만 설치/제어 함수를 사용해야 한다.

seal은 기존 STOP→DRAIN→MIGRATION review, 최신 서명 artifact 집합, 권한 정책, 실행·source/target lock·미확정 HTTP 부재를 요구한다. ProductionCollectionCutover는 기존 OPS ACTIVE 세션과 서명 verifier 및 Fleet/Sender/Archive evidence를 재사용한다. DB 함수는 별도 신뢰된 제어 계정 경계이며 Ed25519 검증 자체를 대신하지 않는다. 실제 signed-store Java 제어 경로를 우회해 artifact를 삽입하는 권한을 앱에 부여하지 않는다.
현재 7개 공식 API는 Java Catalog와 DB seal 모두 차단한다. boolean 환경변수로 audit mode나 승인을 켤 수 없다. 합성 모드는 `collection4c_<UUID>`·서버55433 DB에서만 설치할 수 있다.

FROZEN 중 unknown 실행은 기존 resolution/detail·STOP review와 정확한 HTTP_COMPLETION artifact가 있는 경우에만 `resolve_held`를 통해 FAILED로 정리한다. 시간 만료만으로 해제하지 않는다. 기존 owner는 추정하지 않고 raw/revision/cursor/fence/HTTP/예산 이력을 유지한다. Java bridge는 서명·Fleet·completion snapshot을 먼저 재검증한다. 성공 이력 재작성과 중복 Resolution은 거부한다.

## 트랜잭션과 유지되는 기능
Gateway와 기존 Repository/Projection은 동일 DataSource/JpaTransactionManager를 사용해야 한다. EntityManagerFactory DataSource와 실제 pg_backend_pid도 검사한다. 외부 호출 중 행 잠금을 유지하지 않는다. QUARANTINE 원문·quota 예약은 수신 시 별도 커밋하고, 이후 Projection 실패에 함께 지우지 않는다.
최종 정규화/Revision/Outbox는 기존 OfficialRecordNormalizer·VerifiedIncidentService·DisasterService를 그대로 호출한다. 공식 검증 Port가 없거나 좌표·시각이 무효이면 기존 정책을 유지한다. Gateway는 공식 사건 검증을 대체하지 않는다.
RECEIPT도 새 허가/세대 검증을 통과해야 한다. 만료 후의 늦은 응답으로 일반 쓰기를 허용하지 않는다. 이미 기록된 UNKNOWN/QUARANTINE은 OPS 조사 대상으로 보존한다.

## 운영 전 남은 조건
실제 역할/credential 배포 분리, ACL snapshot과 전체 Fleet 인벤토리 검토, 운영용 비합성 서명/권한/Archive 정책, 7개 API의 공식 계약·사건/증분/구역 매핑, 실제 Sender quota 승인, 서비스별 DataSource/transaction manager 조립 및 후속 전환 승인이 필요하다. 이번 코드로 운영 전환을 실행하지 않는다.
private context·worker capability hash·history의 보존/정리 정책은 별도 승인 대상이다. 자동 삭제는 구현하지 않았다.
## 최종 검증 경계
private 열 단위 ACL 및 Production trigger의 정의/활성 상태도 fingerprint에 포함한다. Trigger는 ALWAYS로 설치하고 앱의 session_replication_role 설정 권한을 거부한다. 인증 세션 철회는 Member→Session→OPS 역할 행 잠금으로 DB 커밋과 직렬화한다. Definer에는 이 고정 SELECT FOR SHARE에 필요한 기본키 열 UPDATE 권한만 별도 검토해야 한다. 앱 역할에는 해당 권한을 주지 않는다.

설치 행이 없고 전환 이력이 전혀 없는 초기 상태만 기존 실행 경로를 유지한다. 설치 행 구성 직후부터 기본 차단하며, 한 번 전환한 뒤 metadata가 유실되면 legacy 경로를 자동 복구하지 않는다. 보관 기간/정리와 잠금 timeout의 실제 배포 값은 별도 정책 승인 대상이며 임의로 확정하지 않았다.

격리 역할 테스트는 임시 NOLOGIN 역할에 SET SESSION AUTHORIZATION을 사용해 PostgreSQL 유효 권한을 검증한다. 실제 운영 LOGIN 인증·credential 배포·네트워크 접근 통제를 검증한 것은 아니다.
