# 담당 영역 분리 결과 (2026-11-03)

## 작업 기준과 Git 상태
- 대상: feature/ai-dashboard-noti, origin/feature/ai-dashboard-noti 추적, HEAD 92828a5d27d8afd4d517ff0c69391315cfc6888f.
- 기존 원격 브랜치를 fetch하고 동일 이름 로컬 추적 브랜치만 생성했다. 추가 기능 브랜치/worktree는 생성하지 않았다.
- cf93adf75feb2576aa8ab157d62b4bb038919583 및 기존 작업 브랜치는 보존했다. 전체 cherry-pick/merge는 수행하지 않았다.
- bd77d83은 대상의 선행 커밋이 아니다. 지역·위치·수집 실제 구현과 수집 raw migration이 대상에 없다.
  타 담당자 선행 파일을 임의 반입하지 않고, 담당 모듈의 독립 코드만 선별했다.
- 변경사항은 모두 미커밋·unstaged 상태다. Commit/Push/Merge/PR/강제 reset은 하지 않았다.
- 원본 eodiisseo-integration 폴더, 실제 .env/키, 운영 DB, 실행 서버는 변경하지 않았다.

## 보호 파일 확인
| 파일 | 대상 브랜치 기준 | 결과 |
| --- | --- | --- |
| auth/service/AuthService.java | 존재 | Git blob과 작업 파일 hash 동일, 변경 없음 |
| collection/service/CollectionRecordWriter.java | 대상에 없음 | 생성/반입하지 않음 |
| area/config/DomainReadAutoConfiguration.java | 대상에 없음 | 생성/반입하지 않음 |

auth/collection/area 도메인 및 인증 담당 테스트의 대상 브랜치 대비 변경은 0개다.
보안 필터/공통 설정/기존 Flyway/빌드 설정/프론트엔드도 변경하지 않았다.

## 유지·분리한 기능
- 공식 재난 목록/상세, 유형·상태·페이지·90일 보존 조회와 공식 원문 Revision 읽기.
- 재난 도메인의 명시적 원문 저장 메서드와 원자적인 Outbox 기록. 실제 수집기의 자동 호출은 연결하지 않았다.
- 요약 영속 작업, PENDING/COMPLETED/FAILED/STALE, 중복·구버전·재시작 처리와 안전한 실패 기록.
- 기존 알림 발송/야간/재시도/Retry-After/제출 승인/기기 토큰 암호화 코드.
  실제 관심지역 Port가 없으므로 운영 발송 연결 완료로 간주하지 않는다.
- 회원 동의, 로컬 폐쇄형 개인정보 최소화, created_at+90일 보존, RAG 자료 승인·철회.
- 전국 Dashboard는 실제 공식 재난을 제공하고 관련성은 UNKNOWN으로 유지한다.
  선택 지역/위치의 관련성 계산을 임의로 구현하지 않았다.
- Collection raw 테이블 삭제는 제거했다. 정규화 재난·원문·요약·Outbox 보존 정리만 담당 모듈에서 유지한다.

OwnedDisasterPortDefaults의 명시적인 Port Bean을 deferred import로 등록한다.
다른 담당자의 설정 파일이나 서비스 호출을 가로채지 않는다. 명시적 Port가 이미 있으면 해당 기본 Bean은 등록하지 않는다.
AOP, 회원/수집 감시 trigger, DB polling, 다른 담당자의 Repository 호출로 자동 연동을 대체하지 않았다.
공유 JDBC helper는 담당 테이블 접근에 사용하며 인증·역할·세션 판단은 기존 SessionAccessService/AuthService를 호출한다.

## API 상태
다음 표는 owned runtime 스키마를 명시 적용하고 eodiisseo.owned-runtime.enabled=true로 검증한 결과다.
기본 운영 설정은 변경하지 않았다.

| API | 현재 가능한 동작 | 남은 조건 |
| --- | --- | --- |
| DASH-001 | 전국 공식 재난 목록 | 위치/관심지역 Service 및 지역 관련성 계약 필요 |
| DASH-002 | 실제 DB 목록·필터·페이지 | 실제 수집 데이터 필요 |
| DASH-003 | 실제 상세·단일 대표 원문·LATEST/오류 | 실제 제공처·신선도·영향지역 자료 필요 |
| AISUM-001 | 실제 Revision 및 저장 요약 상태 | 실제 수집기 호출 연결/모델 검증 필요 |
| CHAT-001 | 동의·승인 근거가 있으면 안내, 그 외 FALLBACK | 실제 자료 승인·동의 UI/API·모델 검증 필요 |
| NOTI-001 | 설정 조회 서비스 유지 | 가입 초기화 호출 및 실제 InterestAreas Port 부재로 현재 실패 |
| NOTI-002 | 원자적 PATCH 서비스 유지 | 실제 InterestAreas Port와 동일 잠금 트랜잭션 참여 필요 |
| NOTI-003 | 암호화 기기 등록 | 별도 실제 기기/발송 검증 |
| NOTI-004 | 본인 기기 삭제·타인 거절 | 별도 실제 기기 검증 |

필수 Port 부재를 빈 목록·허용 값으로 대체하지 않는다.
실제 통합 테스트는 관심지역 Port/위치 Port가 없는 상태와 NOTI 설정·지역 Dashboard의 오류를 명시적으로 확인한다.
모의 관심지역을 운영 구현으로 주입하거나 성공했다고 보고하지 않는다.

## 담당자 협의 필요
1. 인증/회원: 신규 회원 생성 트랜잭션에서 기존 NotificationRetentionService.onMemberCreated 호출 또는 동등한 소유자 제공 lifecycle.
   현재 AuthService에는 호출/이벤트가 없다. 테스트에서 명시적으로 같은 트랜잭션에 호출해 공동 롤백 가능성만 검증했다.
   이것을 자동 회원가입 연동 성공으로 간주하지 않는다. 기존 회원 설정 보정도 소유자가 제공한 대상 목록/Service 계약이 필요하다.
2. 수집: 실제 저장 트랜잭션에서 담당 재난 Service를 호출하는 연결 지점, source registry와 실제 source/revision 정보.
   현재 CollectionRecordWriter 자체가 대상에 없다. 다른 브랜치의 Writer/수집 Service/raw 스키마를 가져오지 않았다.
   수집 원본 raw 보존/삭제 정책과 Service 호출도 수집 담당자 협의 대상이다.
3. 지역/위치: 실제 소유권·삭제·notify_enabled·행정구역 중심점 및 재난 영향지역 관련성 조회 Port.
   관심지역 변경은 회원 잠금/알림 설정과 동일 트랜잭션에 참여해야 한다.
4. DB: 아래 선택적 스키마와 버전 순서·공유 FK·요약 UNIQUE를 배포 전 검토해야 한다.
5. 운영: 야간 종료/재시도/대표 원문/AI 보존 등 개발용 정책 승인, 실제 corpus 검토, 개인정보 동의 UI/API 및 실기기 검증.

## AuthService 변경 없이 동의를 제한한 방법
- ai_external_consent의 granted_session_id에 동의 시 검증한 세션을 기록한다.
- 기록은 SessionAccessService.runWithActiveSession 내부에서 수행한다.
- 허용 여부는 담당 AI 테이블을 읽고, 기존 SessionAccessService.requireActiveSession으로 그 세션의 소유/ACTIVE/만료/폐기를 확인한다.
- 기존 인증 코드가 탈퇴/로그아웃 시 세션을 폐기하므로 동의가 즉시 효력을 잃는다.
  탈퇴 취소로 회원이 ACTIVE가 되어도 과거 세션이 복원되지 않아 과거 동의가 자동 복원되지 않는다.
- 이전 grant에 세션이 없으면 허용하지 않는다. 기존 granted 값을 임의로 덮어쓰지 않았다.
- 회원 단위 영구 동의보다 보수적인 정책이다. 로그아웃/세션 만료 후 재동의가 필요할 수 있어 운영 정책 협의가 필요하다.
- metadata의 즉시 물리 삭제 hook은 연결하지 않았다. 7일 회원 정리 시 기존 회원 FK cascade로 제거되며 실제 기존 AuthService 정리를 검증했다.

자료 검토의 역할·세션 직렬화도 SessionAccessService와 AuthService.me를 사용한다.
별도 회원 테이블 조회/잠금으로 인증 담당자의 권한 판단을 우회하지 않는다.

## 스키마 분리와 영향
기본 db/migration의 기존 파일은 그대로다. 선택적 파일은 db/owned-runtime에 둔다.
- V2026110301__runtime_events_and_ai_policy.sql:
  cf93adf의 원본 내용과 hash가 동일하다. provider 우선순위, Outbox, 요약 작업, 자료 검토, 외부 AI 동의의 5개 테이블,
  delivery metadata, 요약 Revision UNIQUE 및 원문 관계/불변 검증을 포함한다.
  원문 불변 trigger는 담당 원문 테이블의 UPDATE 거절용이며, 회원가입/탈퇴/수집 연동을 수행하지 않는다.
- V2026110302__session_bound_external_ai_consent.sql:
  동의의 granting session 컬럼/FK 추가. auth_session 삭제 시 SET NULL이며 기존 인증 정리 흐름을 막지 않는다.
  동의 값 일괄 초기화나 자동 승인 데이터는 없다.

기본 스키마는 37개 테이블로 기존 인증 테스트와 호환된다.
명시적으로 두 위치를 선택한 테스트 DB는 42개 테이블이다.
eodiisseo.owned-runtime.enabled=true인데 이 스키마가 없으면 OwnedRuntimeSchemaGuard가 기동을 거절한다.

선택 설정 (실제 .env를 수정하지 않음):
- spring.flyway.locations=classpath:db/migration,classpath:db/owned-runtime
- eodiisseo.owned-runtime.enabled=true
- HTTP/알림 persistence/crypto는 기존 플래그와 실제 키 검증을 별도로 적용.
- AUTH_BRIDGES_ENABLED=true 유지. FCM/Ollama/외부 AI/자동 worker는 별도 검증 전 기본 OFF.
- 테스트 설정: src/test/resources/application-owned-runtime-integration.yml (운영 JAR 제외).

공유 DB 적용 전 주의:
- 기존 중복 ai_summary.source_revision_id가 있으면 UNIQUE 적용은 실패한다. 임의 중복 삭제를 하지 않는다.
- cf93adf의 0301을 이미 적용한 DB는 같은 파일을 포함하도록 locations를 유지하고 후속 0302를 검토해야 한다.
  applied migration 누락을 ignore/repair/baseline으로 우회하지 않는다.
- 대상에 없는 bd77d83의 V2026110201 등 선행 migration을 나중에 들여올 경우 버전 순서 검토가 필요하다.
  현재 검증은 대상 92828a5 + 선택적 담당 스키마의 호환성이며, 미래 전체 통합 DB 배포 승인으로 간주하지 않는다.
- 실제 운영 DB에 migration/clean/repair를 실행하지 않았다.

## 검증
- compileTestJava 통과.
- 집중 검증: 담당 분리 통합 5개 + 정책 8개 = 13개 통과, 실패/생략 0.
- 전체 clean build 결과: **930개 중 919 통과 / 실패 0 / 생략 11**, BUILD SUCCESSFUL.
- 집중 검증 13개와 전체 회귀는 이번 대상 브랜치에서 다시 실행했다. cf93adf의 과거 960개 결과를 재사용하지 않았다.
- 생략 11개: Ollama 실호출 3, RAG 실호출 1, Docker/Testcontainers 인증 DB 4,
  FCM OAuth 1, 실제 서비스 계정 기반 wiring 1, 기상청 실호출 1. 생략을 통과로 계산하지 않는다.
- 보고서: C:/Users/dkemf/AppData/Local/Temp/eodiisseo-owned-separation-full-20261103/reports/tests/test/index.html
- 기존 인증/수집 테스트는 대상 브랜치 그대로 실행했다. bd77d83의 실제 지역/수집 코드가 없어 그 구현의 기능 회귀까지 검증했다고 보고하지 않는다.
- 실제 HTTP 테스트는 AUTH_BRIDGES_ENABLED=true와 실제 DB 세션·JWT/OwnedHttpSecurityGuard를 사용한다.
  전역 플래그를 주입하지 않은 조건부 Bean 단위 테스트와 구분한다.
- 임시 테스트 DB/키/포트를 사용했고 실제 .env/인증키/운영 DB/실서버는 변경하지 않았다.

## 기존 49개 파일 재검토

| cf93adf file | 현재 처리 |
| --- | --- |
| `.env.example` | 미반영: 공용 설정은 유지하고 담당 문서에 선택 속성 기록 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/ChatService.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/adapter/ChatRetentionWorker.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/adapter/CreatedAtRetentionPolicy.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/adapter/ExternalAiConsentService.java` | 재구성: 세션 결합 + 인증 Service 사용, AuthService hook/회원 직접 조회 제거 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/adapter/LocalIntentPrivacy.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/port/ChatOutboundConsentPort.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/port/ChatPromptPrivacyPort.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/DatabaseResourceApproval.java` | 재구성: 기존 인증 Service의 직렬화/역할 확인 사용 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/summary/adapter/ConfiguredSummaryRetry.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/summary/adapter/DatabaseSummaryFailures.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/ai/summary/adapter/DurableSummaryTasks.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/area/config/DomainReadAutoConfiguration.java` | 미반영: 보호 파일, 대상에 없는 파일을 반입하지 않음 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/auth/service/AuthService.java` | 미반영: 보호 파일, 대상 내용 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/collection/service/CollectionRecordWriter.java` | 미반영: 보호 파일, 대상에 없는 파일을 반입하지 않음 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/DashboardHttpErrors.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/controller/DashboardController.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/dto/DisasterListQuery.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/http/OPERATIONS.md` | 미반영: 이전 전체 통합 보고서 대신 본 문서로 현황 기록 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/http/README.md` | 미반영: 이전 전체 통합 보고서 대신 본 문서로 현황 기록 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/http/RUNTIME_IMPLEMENTATION.md` | 미반영: 이전 전체 통합 보고서 대신 본 문서로 현황 기록 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/adapter/DisasterOperationalAdapters.java` | 재구성: 명시 opt-in 및 담당 모듈 deferred Port 등록 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/adapter/NormalizedDisasterReader.java` | 미반영: 타 담당 Area 구현 의존. 담당 모듈의 전국 조회/명시 Port로 대체하고 지역 연동은 보류 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterRetentionService.java` | 재구성: 존재하지 않는 수집 raw 테이블 삭제 제거 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterRuntimeEvents.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterRuntimeWorker.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterService.java` | 유지: 담당 정규화/Outbox 메서드, 외부 수집기의 호출 연결은 협의 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/OfficialDisasterQueryService.java` | 유지·확장: 담당 카탈로그/전국 조회, 타 담당 Repository 접근 없음 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/RuntimeRetentionWorker.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationContracts.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationDispatchService.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationRetentionService.java` | 대상 기존 구현 유지: 가입 hook 자동 연결 및 별도 fallback 추가 안 함 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationRetryWorker.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationSettingsRepairService.java` | 미반영: 회원 목록 직접 조회 대신 소유자 Service/대상 목록 협의 필요 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationSignupInitializer.java` | 미반영: 자동 가입 연동은 담당자 협의, 기존 Store 초기화 재사용 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/adapter/DevelopmentNotificationPolicies.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/adapter/FcmHttpV1Sender.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/java/com/disasterinfo/eodiisseo/notification/persistence/JpaNotificationStore.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/main/resources/db/migration/V2026110301__runtime_events_and_ai_policy.sql` | 내용 동일 보존, db/owned-runtime으로 분리하여 기본 schema/history에 자동 적용하지 않음 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/ai/chat/ChatRagIntegrationTest.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/ai/chat/ChatServiceTest.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/ai/http/AiHttpControllersTest.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/auth/repository/CanonicalDatabaseIntegrationTest.java` | 미반영: 타 담당자 테스트 원본 유지, 기본 스키마 37개 검증 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/dashboard/PostgresAuditSupport.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/disaster/RuntimeOperationsIntegrationTest.java` | 미반영: 보호 코드 setter/Collector/Area 의존. 실제 미연결을 검증하는 새 owned 통합 테스트 작성 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationCanonicalHttpIntegrationTest.java` | 대상 기존 테스트 유지: 없는 AreaService에 의존하는 추가 case는 새 분리 테스트로 대체 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationFixture.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/notification/OperationalPoliciesTest.java` | 담당 모듈 구현/테스트 유지 |
| `backend/src/test/java/com/disasterinfo/eodiisseo/notification/adapter/FcmFailureClassificationTest.java` | 담당 모듈 구현/테스트 유지 |

## 현재 변경 파일 목록

현재 변경 파일은 모두 담당 모듈·대응 테스트·선택적 신규 스키마/테스트 profile·본 문서다.
아래 목록은 미커밋 상태이며 사용자 검토를 위해 stage하지 않았다.

Total: 41 files (16 modified, 25 new).

- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/ChatService.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/adapter/ChatRetentionWorker.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/adapter/CreatedAtRetentionPolicy.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/adapter/ExternalAiConsentService.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/adapter/LocalIntentPrivacy.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/port/ChatOutboundConsentPort.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/port/ChatPromptPrivacyPort.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/DatabaseResourceApproval.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/summary/adapter/ConfiguredSummaryRetry.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/summary/adapter/DatabaseSummaryFailures.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/summary/adapter/DurableSummaryTasks.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/DashboardHttpErrors.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/controller/DashboardController.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/dto/DisasterListQuery.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/http/OWNERSHIP_SEPARATION.md`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/adapter/DisasterOperationalAdapters.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/adapter/OwnedDisasterPortDefaults.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterRetentionService.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterRuntimeEvents.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterRuntimeWorker.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterService.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/OfficialDisasterQueryService.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/OwnedRuntimeSchemaGuard.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/RuntimeRetentionWorker.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationContracts.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationDispatchService.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationRetryWorker.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/adapter/DevelopmentNotificationPolicies.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/adapter/FcmHttpV1Sender.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/persistence/JpaNotificationStore.java`
- `backend/src/main/resources/db/owned-runtime/V2026110301__runtime_events_and_ai_policy.sql`
- `backend/src/main/resources/db/owned-runtime/V2026110302__session_bound_external_ai_consent.sql`
- `backend/src/test/java/com/disasterinfo/eodiisseo/ai/chat/ChatRagIntegrationTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/ai/chat/ChatServiceTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/ai/http/AiHttpControllersTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/dashboard/PostgresAuditSupport.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/disaster/OwnedRuntimeSeparationIntegrationTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationFixture.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/OperationalPoliciesTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/adapter/FcmFailureClassificationTest.java`
- `backend/src/test/resources/application-owned-runtime-integration.yml`
