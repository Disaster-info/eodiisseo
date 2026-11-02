# AI 대시보드 알림 HTTP 통합과 남은 계약

2026년 11월 2일 `3359592` 기준이다. Controller 구현, 실제 인증 보호와 DB 검증, 운영 데이터 연결 완료를 구분한다.
사용자 지시에 따라 브랜치 변경·커밋·Push·Merge를 하지 않는다. 인증 담당 소스는 별도 승인된
`AssignedSecurity.securityMatcher` 경로 추가만 수정하고 나머지 인증·지역·위치·재난·수집·스키마는 보존한다.

## HTTP 활성화

기본값은 비활성이다. 아래 속성을 명시할 때 9개 Controller 매핑이 등록된다.

```text
eodiisseo.owned-http.enabled=true
AUTH_BRIDGES_ENABLED=true
```

알림의 실제 저장 및 기기 등록에는 기존 설정도 필요하다.

```text
eodiisseo.notification.persistence.enabled=true
eodiisseo.notification.crypto.enabled=true
```

실제 `.env`와 기존 키 파일은 수정하지 않는다. 설정값 존재를 실제 원문·관심지역·수집·실기기 수신 완료로 간주하지 않는다.
`OwnedHttpSecurityGuard`는 첫 번째로 적용되는 실제 보안 체인에 기존 SessionAuthenticationFilter와
AuthorizationFilter가 있어야 하며, 각 API의 익명 요청을 실제 AuthorizationManager가 거절하는지 검사한다.
네 가지 인증 Port 구현은 각각 하나여야 한다. 누락·중복·permitAll·보안 경로 누락 시 웹 서버 시작 전에 실패한다.
가드는 토큰을 만들거나 요청 ID를 신뢰하지 않으며 실제 SecurityContext를 변경하지 않는다.

| API | Method | 경로 |
| --- | --- | --- |
| API-DASH-001 | GET | /api/v1/dashboard |
| API-DASH-002 | GET | /api/v1/disasters |
| API-DASH-003 | GET | /api/v1/disasters/{disasterId} |
| API-AISUM-001 | GET | /api/v1/disasters/{disasterId}/ai-summary |
| API-CHAT-001 | POST | /api/v1/ai/guidance |
| API-NOTI-001 | GET | /api/v1/notification-settings |
| API-NOTI-002 | PATCH | /api/v1/notification-settings |
| API-NOTI-003 | PUT | /api/v1/notification-devices/{deviceId} |
| API-NOTI-004 | DELETE | /api/v1/notification-devices/{deviceId} |

Controller는 검증된 MemberPrincipal의 memberId/sessionId만 사용한다. 요청의 회원·세션 값으로 인증을 대체하지 않는다.
성공 DTO·기존 오류 코드·상태 계약을 유지한다. 삭제는 본인 기기만 가능하며 204를 반환한다.
권한·의존성 장애를 정상 목록이나 임의 자료로 대체하지 않는다.

## 알림 인증과 부분 변경

`AUTH_BRIDGES_ENABLED=true`이면 기존 AuthenticationPortsConfiguration이 우선한다.
`eodiisseo.notification.auth.enabled=true`가 함께 있어도 알림 인증 Bean은 하나다.
레거시 어댑터만 켰을 때도 AuthRepository를 직접 사용하지 않고 기존 SessionAccessService로 검증한다.

PATCH는 기존 enabled/interestAreaIds/minimumSeverity/nightNotificationEnabled만 허용한다.
미포함 필드는 유지하고 명시적 minimumSeverity:null은 기존 내부 계약에 따라 최소 심각도를 해제한다.
나머지 필수 필드의 명시적 null, 잘못된 JSON 타입, 알 수 없는 필드는 400이다.
회원 잠금을 획득한 뒤 기존 설정을 읽고 병합·검증·저장하므로 동시 부분 변경이 서로의 값을 덮어쓰지 않는다.
잠금 기능을 제공하지 않는 Store는 성공을 가장하지 않는다.

## 운영 원문과 데이터 선행조건

새 통합본에는 canonical Flyway, 실제 세션 검증 Service, AES-GCM 및 기존 FCM sender가 있다.
반면 DisasterService/AreaService/LocationService/CollectionService의 조회·실행 기능은 아직 골격이다.
Dashboard/AI/알림이 다른 담당 Repository를 직접 조회하는 어댑터는 추가하지 않는다.

담당자에게 필요한 최소 읽기 계약은 다음과 같다. DTO 이름은 제안이며 실제 제공된 메서드가 아니다.

```java
boolean existsDisasterType(String typeCode);
DisasterListView findDisasters(DisasterFilter filter);
Optional<DisasterDetailView> findDisaster(UUID disasterId);
List<DisasterCandidateView> findCandidates(UUID memberId, LocationCriteria basis);
Optional<OfficialSourceView> findOfficialSummarySource(UUID disasterId);
Optional<OfficialRevisionView> findOfficialRevision(UUID revisionId);
boolean isCurrentRevision(UUID revisionId);
List<EndedDisasterView> findEndedAtOrBefore(Instant inclusiveCutoff);
LocationView resolveCoordinates(double latitude, double longitude);
LocationView resolveOwnedInterestArea(UUID memberId, UUID interestAreaId);
List<NotificationAreaView> findOwnedNotificationAreas(UUID memberId);
void replaceNotificationSelection(UUID memberId, Set<UUID> selectedIds);
boolean isDisasterRelated(UUID disasterId, UUID interestAreaId);
Optional<SeverityView> findSeverityByCode(String code);
Optional<SeverityView> findSeverityById(UUID severityId);
```

재난 DTO에는 고유 UUID, 공식/Mock 구분, 유형·상태·공식 심각도·발생/갱신 시각·실제 신선도가 필요하다.
원문 버전은 disasterId/sourceDisasterId/revisionId/revisionNo와 정확한 원문, 현재 버전·대표 제공처를 제공해야 한다.
여러 제공처 원문을 AI가 임의로 합치지 않는다. 종료 조회에는 실제 endedAt이 필요하다.
관심지역은 회원 ACTIVE·소유·삭제 상태와 실제 행정구역 중심점을 매번 확인하고, 알림 선택 변경은 설정 트랜잭션에 참여해야 한다.
재난 저장 커밋 후 변경 이벤트를 전달할 실행 지점은 수집 담당자가 제공해야 한다. 수집 코드에 임의 트리거를 삽입하지 않았다.

## 공식 자료와 미확정 정책

RAG의 기존 JDBC 저장·검색·문단 검증을 재사용한다. 체크리스트 seed의 출처 안내문은 승인된 행동요령 corpus가 아니다.
실제 공식 원문 적재, ResourceApprovalPort, ChatPromptPrivacyPort 및 ChatRetentionPolicy 운영 공급자는 여전히 필요하다.
승인·개인정보 외부 전달 동의·보존정책을 자동 허용하지 않는다. 근거 부족 시 기존 안전한 대체 정책을 유지한다.

첨부 지시서의 PROCESSING은 기존 SummaryStatus에 없으므로 기존 PENDING/COMPLETED/FAILED/STALE 계약을 유지한다.
API 5.2.1 원문이 현재 저장소에 없어 세부 계약 대조가 필요하며, 새 enum이나 응답 필드를 임의로 추가하지 않았다.
CHAT 생성 시점과 created_at 요청 시각의 90일 보존 기준 차이, 요약 durable dispatcher·재시도·오류 기록,
알림 야간 종료·자동 재시도·재시작 복구 정책은 합의가 필요하다.

## FCM 결과와 재시도

기존 FCM HTTP v1 sender를 재사용해 ACCEPTED/UNREGISTERED/인증 오류/요청 오류/429/500·503/타임아웃/접수 불명을 구분한다.
ACCEPTED는 FCM 접수이며 실제 기기 표시 완료가 아니다. 명시적인 typed UNREGISTERED만 토큰을 비활성화한다.
PENDING, 접수 불명, 타임아웃, 이전 미분류 FCM_SEND_FAILED 기록은 재전송하지 않는다.
429 및 일시적 실패도 기존 명시적 RetryPolicy가 허용할 때만 대상이며 새 횟수·간격·스케줄은 적용하지 않는다.
기존 3인자 PushResult 생성자는 유지하고 증거 없는 실패는 접수 불명으로 취급한다.
공식 오류 분류 근거: https://firebase.google.com/docs/cloud-messaging/error-codes

서비스 계정 및 토큰 암호화 키는 기존 파일을 사용한다. 실기기 토큰이 없으면 실제 Push를 보내지 않는다.
OAuth-only 검증은 명시적인 테스트 환경 변수로 별도 실행하며 토큰·개인키를 출력하지 않는다.

## 검증과 실행 한계

실제 보안 필터를 사용하는 HTTP 테스트, canonical Flyway/JPA validate, 별도 audit DB의 동시성·UNIQUE·외래키 테스트를 구분한다.
테스트의 재난·관심지역·RAG 승인·보존정책 fixture는 운영 구현이 아니다.
일반 빌드는 실행 중인 백엔드 로그 잠금을 피하도록 검증용 buildOutputDir로 분리한다.

```powershell
cd backend
.\gradlew.bat clean build --console=plain -PbuildOutputDir=<검증용_임시_디렉터리>
cd ..
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/database/Test-LocalDatabase.ps1 -BuildOutputDir <별도_DB_검증_디렉터리>
```

운영 DB에 clean/repair/baseline을 실행하지 않는다. audit 클러스터와 disposable 테스트 DB만 사용한다.
실제 기기 로그인·Push 표시, 승인 원문 corpus, 재난 수집·GIS·관심지역 운영 연동은 별도 검증이 남는다.
## 최종 검증 결과

- 기준 HEAD: 3359592, 작업 브랜치: integration/backend-develop 그대로. 사용자 최종 지시로 브랜치 변경·커밋·Push·Merge·PR 생성 없음.
- 기존 build/local 백엔드 로그가 실행 중인 프로세스에 잠겨 기본 clean은 실패했다. 기존 프로세스를 종료하지 않고 검증용 임시 buildOutputDir로 분리했다.
- 최종 clean build: 915개 중 870 통과, 실패 0, 환경 의존 45 생략.
- 기존 Test-LocalDatabase.ps1 스크립트의 CanonicalDatabaseIntegrationTest 필터: 9개 통과.
- 별도 PostgreSQL 17/PostGIS 전체 build: 915개 중 905 통과, 실패 0, 생략 10.
- CanonicalDatabaseIntegrationTest 9개, LocalApplicationIntegrationTest 1개, PostgresApplicationStartupTest 1개,
  NotificationCanonicalHttpIntegrationTest 1개, NotificationSettingsPatchPostgresTest 1개 모두 실제 audit DB에서 통과.
- 실제 서비스 계정 Spring wiring와 기존 키 파일 존재 확인. 사용자 명시 승인 후 FcmLiveOAuthSmokeTest 1개 별도 통과.
  토큰·개인키·비밀번호 출력, 실제 기기 Push 전송은 하지 않았다.
- DB 전체 실행의 생략 10개는 Ollama/RAG 실호출 4개, Docker/Testcontainers 인증 DB 4개,
  별도 실행한 OAuth 1개, 이번 범위에서 실행하지 않은 기상청 실호출 1개다.
- 생성된 테스트 DB·스키마는 해당 테스트가 정리했다. 운영 DB와 기존 Flyway 이력은 변경하지 않았다.
- 정상 HTTP 본문과 오류 code/message/traceId, 실제 익명·만료·폐기·권한 거절, 암호화·UNIQUE·두 회원 격리와 PATCH 동시성이 검증됐다.
  지역·심각도 및 원문·보존정책 테스트 fixture를 운영 연동 완료로 간주하지 않는다.

검증 보고서:

- 일반: `C:/Users/dkemf/AppData/Local/Temp/eodiisseo-http-integration-20261102/reports/tests/test/index.html`
- DB 포함: `C:/Users/dkemf/AppData/Local/Temp/eodiisseo-db-http-integration-20261102/reports/tests/test/index.html`
- OAuth 별도: `C:/Users/dkemf/AppData/Local/Temp/eodiisseo-fcm-oauth-20261102/reports/tests/test/index.html`

기존 실행 서버와 실제 `.env`는 변경하지 않았다. 실제 서비스 사용에는 위 활성화 속성을 적용한 재시작과 운영 데이터 Port 연결이 필요하다.
알림 가입 기본 설정 초기화는 인증/회원 생성 담당자가 기존 NotificationRetentionService.onMemberCreated와 연결해야 한다.
FCM 접수 직전 재검증은 유지하지만 실제 제출과 탈퇴·기기 변경을 원자적으로 직렬화하는 수명주기 계약은 추가 검토가 필요하다.

## 변경 파일

Controller·가드, 알림 인증 중복·PATCH·결과 분류, 오류 처리, 대응 회귀 테스트와 본 문서만 변경했다.
인증 담당 소스는 별도 승인된 AssignedSecurity matcher의 경로 추가 한 곳이다.
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/chat/controller/ChatController.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/http/AiExceptionHandler.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/README.md`
- `backend/src/main/java/com/disasterinfo/eodiisseo/ai/summary/controller/SummaryController.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/auth/config/AssignedSecurity.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/controller/DashboardController.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/http/OwnedHttpSecurityGuard.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/dashboard/http/README.md`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/adapter/FcmHttpV1Sender.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/adapter/RepositoryNotificationAuthentication.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/adapter/ServiceAccountFcmTokens.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/controller/NotificationController.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/dto/NotificationDtos.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationContracts.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationDispatchService.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationHttpErrors.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/NotificationService.java`
- `backend/src/main/java/com/disasterinfo/eodiisseo/notification/persistence/JpaNotificationStore.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/ai/http/AiHttpControllersTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/dashboard/DashboardAssignedSecurityReadinessTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/dashboard/OwnedHttpSecurityGuardTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/dashboard/PostgresApplicationStartupTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/adapter/FcmAdapterTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/adapter/FcmFailureClassificationTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/adapter/RepositoryNotificationAuthenticationTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationCanonicalHttpIntegrationTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationControllerTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationDispatchTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationFcmOutcomeTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationFixture.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationSafetyRegressionTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationSettingsPatchPostgresTest.java`
- `backend/src/test/java/com/disasterinfo/eodiisseo/notification/NotificationSettingsPatchTest.java`
