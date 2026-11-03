> 3차 정정(2026-11-03): MOIS 공개 API 설명서 탭에서 crtDt(조회시작일자, YYYYMMDD)를 확인했습니다. 아래 “날짜 필터 미확인/없음”은 당시 확인 부족에 따른 기록이며 현재 판정은 PHASE3_CONTRACT_REPORT.md를 따릅니다. 날짜 경계·수정 자료 재조회·안정된 페이지 계약은 여전히 미확인이라 자동 증분 차단은 유지합니다.

# 공식 재난 수집·RAG·Ollama 통합 검증 보고서

검증일: 2026-11-03. 작업 경로: C:/Users/dkemf/source/eodiisseo-integration-ops-latest.
브랜치 feature/ai-dashboard-noti, HEAD 943a19c. 기존 cf93adf 커밋 보존.
이번 작업은 unstaged로 남겼다. 브랜치/Worktree 생성, Stage, Commit, Push, Merge, PR, Reset, Stash를 수행하지 않았다.

## 판정 및 실호출 범위

사용자의 추가 지시 “실 호출은 하지 말고 테스트만 해”에 따라 공공 API·Ollama·FCM 실호출은 하지 않았다.
공개 API 설명서는 읽었으나 인증키를 사용하지 않았다. 운영 DB, 실제 .env, 서비스 계정과 실행 서버를 변경하지 않았다.
테스트의 제공처 응답, 행동요령과 모델 응답은 명시적 합성 Fixture다. 운영 데이터로 등록하지 않았다.
실제 공식자료 확보 0건, 신규 운영 승인 0건, 실제 설치 모델 확인/실모델 요약 생성 미검증.
따라서 **세 재난의 운영 데이터 수집과 실제 모델 운영 전체 완료로 판정하지 않는다.**

## 기존 구현 재사용과 추가 내용

- DisasterService.earthquake(), DisasterRuntimeEvents, 공식 조회 Service, SummaryService, DurableSummaryTasks,
  DisasterRuntimeWorker, 기존 인증·ACTIVE 세션 검증, 토큰 암호화, 9개 Controller를 유지했다.
- 고정된 공식 HTTPS endpoint별 클라이언트 계약, JSON/안전한 XML 파서, 제공처 결과코드, 전체 페이지 수 검증,
  원문 바이트 보존, 정렬된 항목 JSON, SHA-256, 발표 ID 기반 중복 방지를 추가했다.
- 전송 연결 5초/전체 요청 15초, 응답 최대 2 MiB, 페이지당 100건·최대 100페이지/10,000건,
  클라이언트 내 요청 간 최소 1초, 제공처별 UTC 일일 요청 예약 상한 1,000회를 적용했다.
  상한은 서비스의 기술적 보호값이며 실제 승인 트래픽과 다른 클라이언트 사용량까지 보장하는 값은 아니다.
- 성공 뒤 최소 60초, 실패 뒤 300초 재시도 대기. 즉시 재시도하지 않으며 일일 예산은 재시작 후에도 보존한다.
- canonical collection_job_lock의 LIVE 잠금·run/source/mode FK를 재사용하여 유효한 타 실행을 덮어쓰지 않는다.
  30분 임대 만료 후 새 실행이 이전 RUNNING을 FAILED/LEASE_EXPIRED로 정리한다. 중단 시 재개에 최대 임대시간이 필요할 수 있다.
- 전체 조회가 끝난 뒤 원문 페이지·항목·정규화·Revision·Outbox·진행일·SUCCESS를 한 트랜잭션으로 반영한다.
  실패는 별도 트랜잭션의 안전한 코드로 남기며 직전 성공 데이터/진행일을 보존한다.
  HTTP/스키마 오류로 전체 수집이 실패한 응답은 원문 저장 완료로 계산하지 않는다.
- 수집과 Dashboard 조회를 분리했다. Dashboard 요청이 공공 API를 호출하지 않는다.
  담당 disaster 상세 어댑터는 수집 실패/노후화를 LATEST 대신 STALE로 반환한다.
  원문 자체가 없는 경우의 오류 및 알림 발송의 기존 신선도 검증은 유지한다.
- OPS·ACTIVE 세션을 요구하는 명시적 HTML 가져오기 Service를 추가했다.
  원본 바이트와 본문을 분리하고, 명시한 body element ID의 문단만 추출한다.
  원문 변경 시 새 UUID/버전을 발행하고 같은 URL의 이전 승인을 철회한다.
  기존 ResourceService의 승인·fingerprint·정확한 문단 일치·FALLBACK 정책을 재사용한다.
  미승인·만료·대체된 가져오기 자료의 승인/검색을 허용하지 않는다.
- OllamaChatClient에 명시적 /api/tags 모델 존재 점검을 추가했다. 설치 이름과 설정 이름이 정확히 일치해야 한다.
  자동 호출/모델 다운로드/모델명 추정은 없다. 기존 /api/chat 전송과 호스트 제한을 유지했다.

## 제공처별 상태와 남은 계약

| 항목 | 원천 / endpoint_key | 구현·테스트 상태 | 운영 전 필요한 계약 |
|---|---|---|---|
| 지진 | KMA EqkInfoService/getEqkMsg / KMA_EARTHQUAKE | 발표별 수집·저장, 동일 발표 중복 방지·정정 Revision·Outbox·AI 흐름 검증 | stnId:tmFc:tmSeq는 발표 식별자다. 발표시각/번호가 다른 후속 발표를 같은 지진으로 합치지 않는다. 공식 사건 ID/참조번호의 연도·월·발표 간 연결 규칙 확인 필요 |
| 호우 | KMA WthrWrnInfoService/getPwnCd / KMA_HEAVY_RAIN | warnVar=2, warnStress 0/1, command 1/2/3/6/7/8, cancel=0 처리. 동일 구역의 발효시각으로 시작 기록을 구분하고 정렬된 변경·해제 연결 검증 | 검증된 특보구역→행정구역 매핑 및 공식 심각도 매핑 필요. 현재 구역 코드/명은 보존하지만 IMPACT 영역·공통 severity를 추정하지 않는다 |
| 산불 문자 | MOIS /V2/api/DSSP-IF-00247 / MOIS_DISASTER_MESSAGES | SN별 원문·DST_SE_NM 등 전체 필드 보존, 산불 문자/그 외 미분류 보존 검증. 사건 자동 등록 미연결 | 공개 계약의 SN은 문자 ID다. 공식 사건 ID, 발송기관 필드, 후속·종료 연결 계약 필요. 수신지역은 영향지역이 아니다. 키워드로 발생·확산·대피·진화 사실이나 발송기관을 확정하지 않는다 |

호우의 선행 발표가 없거나 발효가 미래인 경우, 취소/알 수 없는 코드/역순 발표는 원문으로 보존하고 정규화하지 않는다.
해제가 발효 이전/미래이면 적용하지 않는다. 이전 발표 없이 과거 구간을 일부만 재수집하면 정상 연결이 보류될 수 있다.
행정구역 이름 유사도로 연결하거나 규모를 severity로 바꾸지 않는다.

문자 API의 공개 샘플은 페이지 기반이며 날짜 필터 계약은 이번에 확인하지 못했다. 날짜 필터를 임의 추가하지 않았다.
전체 결과가 10,000건을 넘거나 페이지가 불완전/변경되면 실패 처리한다.
따라서 대량 문자 자료의 실시간 연속 수집·재시작 구간 복구는 날짜/수정시각 필터 또는 안정된 cursor 계약을 받은 뒤 확장해야 한다.
요청 창은 KMA에서 최대 3일로 제한하고 성공한 completed_through로 이어가며 하루 중첩 재수집한다.
수동 내부 collect()로 승인된 구간을 다시 요청할 수 있다. 제공처의 실제 과거 조회 가능 기간/트래픽은 실호출 미검증이다.

참고한 공개 계약:
- [기상청 지진정보 조회서비스](https://www.data.go.kr/data/15000420/openapi.do)
- [기상청 기상특보 조회서비스](https://www.data.go.kr/data/15000415/openapi.do), 특보코드조회 getPwnCd
- [행정안전부 긴급재난문자](https://www.safetydata.go.kr/disaster-data/view?dataSn=228)

요구사항 1.2.0·기능명세 2.2.1·ERD 4.2.1·API 5.2.1의 최신 원본 전체는 이번 작업에서 확보하지 못했다.
저장소 초안을 최신 명세로 간주하지 않았다. STALE 표시는 이번 첨부의 명시적 요구에 따른다.

## RAG 및 모델 검증 구분

HTML importer는 자동 실행/자동 승인되지 않는다. fetch(..., environment="development") 또는 audited offline HTML publish()를
OPS 내부 코드에서 명시적으로 호출하는 Service다. 운영 콘솔/새 공개 HTTP endpoint는 추가하지 않았다.
실제 원문과 이용조건, 기관, 본문 element ID, 외부 AI 전송 권리·최신성·인젝션을 사람이 검토해야 한다.
정적 UTF-8 HTML만 지원한다. JS 전용 본문, PDF/HWP 등은 본문이 없거나 형식이 다르면 거부하며 별도 파서 계약이 필요하다.
가져오기 만료는 호출자가 명시하며 현재 시점부터 최대 90일 이내만 받는다. 동일 바이트 재가져오기는 기존 ID를 반환하며
만료/대체된 승인을 되살리지 않는다. purgeExpired()는 승인을 철회하고 취득 원문 바이트를 지운다.
이미 인용된 불변 official_resource 본문은 기존 인용 보존 정책 때문에 자동 삭제하지 않는다. 정책 합의가 필요하다.
일반 행동요령과 발생 원문을 서로 다른 테이블/흐름으로 유지하며, 현재 위험·안전 경로·영향지역을 RAG로 추정하지 않는다.

루프백 통합 검증에서는 제공처 대역 HTTP 서버 → 실제 OfficialHttp → 정규화/DB → Outbox → 실제 Worker/영속 작업 →
실제 OllamaChatClient /api/chat → OllamaSummaryGenerator 검증 → ai_summary COMPLETED → 인증된 조회를 확인했다.
실모델 대신 대역 서버가 허용 문단을 그대로 반환했다. source_disaster 원문은 요약으로 덮어쓰지 않았다.
기존 재시작·HELD·동의·승인·원문 변경 시 STALE 회귀도 통과했다. 실제 한국어 공식 corpus/실모델 품질 테스트는 미검증이다.

## DB 영향

신규 migration: V2026110303__owned_collection_and_resource_import.sql.
V2026110301, V2026110302 및 공용 migration은 변경하지 않았다.
선행 canonical + owned 0301/0302 이후에 적용되며 운영 DB에 적용하지 않았다.

추가 4개 테이블:
- owned_collection_cursor: 성공 진행일, 재시도 대기, 실패 코드, 일일 요청 예산.
- owned_collection_page: 실행별 원문 바이트. collection_run 삭제 시 CASCADE.
- owned_collection_item: 제공처 기록 ID+내용 hash UNIQUE, canonical JSON, 분류, 정규화 FK. 재난 삭제 시 연결 FK는 SET NULL.
- owned_resource_import: 원문 바이트, URL+hash UNIQUE, 만료·대체 메타데이터. official_resource 삭제 시 CASCADE.

기존 공용 테이블의 컬럼/제약/데이터는 바꾸지 않는다. 정상 수집 시 data_source의 승인된 등록을 읽고
collection_run/job_lock, 담당 disaster·revision·change·outbox, official_resource 및 담당 review에 데이터를 쓴다.
이 쓰기 주체·등록 정보·공통 수집 이력 보존 정책은 수집/DB 담당자와 합의해야 한다.
수집 페이지/항목 원문의 운영 보존기한 및 대량 문자 페이지 계약은 미합의다. 무제한 운영 활성화하지 않는다.
OwnedRuntimeSchemaGuard가 새 4개 테이블도 요구한다. 0303 없이 새 코드의 owned-runtime을 활성화하면 기동을 거부한다.
테스트 DB에서 canonical 37개, 전체 owned 적용 시 46개 업무 테이블을 검증했다. 실제 DB의 checksum/기존 데이터 업그레이드는 미검증이다.

## 9개 HTTP API 영향

Controller 신규 등록이나 보안 설정 변경은 없다. 기존 opt-in 및 OwnedHttpSecurityGuard를 유지한다.

| API | 담당 데이터 경로 상태 |
|---|---|
| GET /api/v1/dashboard | 기존 전국 조회 유지. 위치/관심지역 미연결은 기존 오류/UNKNOWN 정책 유지 |
| GET /api/v1/disasters | 저장된 공식 재난 조회 유지. 산불 사건은 자동 생성하지 않아 이 수집기로는 산불 목록을 채우지 못함 |
| GET /api/v1/disasters/{id} | 기존 공식 상세 유지, 노후/실패 수집은 STALE로 표시 |
| GET /api/v1/disasters/{id}/ai-summary | Revision/Outbox/영속 작업 연결 유지. 실제 모델 활성화 전 완료를 보장하지 않음 |
| POST /api/v1/ai/guidance | 승인 자료·외부 처리 동의가 없으면 FALLBACK 유지 |
| GET /api/v1/notification-settings | 관심지역 Port 선행조건 미해결 |
| PATCH /api/v1/notification-settings | 관심지역 Port 선행조건 미해결 |
| PUT /api/v1/notification-devices/{deviceId} | 기존 인증·소유권·암호화 회귀 유지 |
| DELETE /api/v1/notification-devices/{deviceId} | 기존 인증·소유권 회귀 유지 |

실제 인증/보안 검증을 끄거나 임시 사용자를 운영 코드에 추가하지 않았다.
위치·관심지역, 회원 생성 알림 기본값 hook, 실제 발송은 이번 작업으로 연결 완료되었다고 판단하지 않는다.

## 최종 테스트

실행:
```powershell
./backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain -PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-official-final-20261103
```

테스트 전용 환경:
EODIISSEO_LOCAL_DB_TESTS=true, EODIISSEO_AUDIT_DISPOSABLE=true,
EODIISSEO_AUDIT_JDBC_URL=jdbc:postgresql://127.0.0.1:55433/postgres.
해당 폐기용 cluster에서 생성한 owned_<uuid> DB만 사용/제거했다.
실제 인증키 대신 합성 값과 테스트 임시 토큰 암호화 키를 사용했고 제거했다.
EODIISSEO_AI_OLLAMA_LIVE_TEST=false, EODIISSEO_FCM_LIVE_OAUTH_TEST=false,
EODIISSEO_LIVE_WEATHER_TESTS=false, FCM/Ollama 자동 활성화 false.

최종 clean build **성공: 전체 966, 통과 955, 실패 0, 생략 11**.
생략을 성공으로 계산하지 않았다.
- 실제 Ollama 3, RAG 실모델 1: 사용자 지시에 따라 실호출 비활성.
- Docker 기반 AssignedDatabaseIntegrationTest 4: 실행 조건 미충족. 별도의 폐기용 PostgreSQL 통합 검증은 실행됨.
- FCM OAuth 1, 실제 자격증명 wiring 1: 실호출/실자격증명 사용 안 함.
- 실제 KMA 날씨 1: 실호출 비활성.

추가 검증: HTTP/제공처 인증 오류(게이트웨이 XML 포함), 429/5xx, 빈·깨진·XXE 응답,
KST 날짜, 키 인코딩, timeout/redirect, 반복·정정·해제, raw/정규화 transaction rollback,
일일 예산/기존 임대 충돌 시 요청 0회, human review·철회·만료·권한 부족.
새 테스트 코드는 총 36건 늘었으며 기존 OwnedRuntimeSeparationIntegrationTest는 5→10건이다.
수정 도중 발견한 컴파일/날짜 예외/테스트 proxy stubbing 오류는 수정 후 최종 전체 실행으로 재검증했다.
실행 JAR 내 테스트 Fixture/owned-runtime-integration 설정 포함 0건.
리포트: C:/Users/dkemf/AppData/Local/Temp/eodiisseo-official-final-20261103/reports/tests/test/index.html

기존 담당 DashboardAssignedSecurityReadinessTest에서 Mock AuthService에 상속된 @Scheduled 보존 작업이
verifyNoInteractions와 경쟁하여 전체 재실행 중 1건 실패한 이력이 있다(그 실행: 954 통과/1 실패/11 생략).
해당 HTTP slice에만 대역 TaskScheduler를 주입하여 비HTTP 백그라운드 호출을 분리했다.
요청의 JWT/ACTIVE 세션·만료·폐기·권한 검증 및 401 기대값은 바꾸지 않았다.
실제 보존 작업은 별도 폐기용 DB 통합 테스트에서 계속 검증한다.
특보구역 SQL LIKE를 정확한 코드 비교로 바꾸고 와일드카드 문자와 다른 구역 간 오연결 회귀도 보강했다.

## 개발 환경 활성화 절차 — 이번에는 실행하지 않음

1. 위 미완료 계약, 원문 보존기한, 수집 소유권 및 정확한 제공처 등록을 먼저 합의한다.
2. 별도 개발 DB에서 spring.flyway.locations=classpath:db/migration,classpath:db/owned-runtime를 적용·검토한다.
   기존 migration checksum을 바꾸거나 repair/clean/baseline으로 우회하지 않는다.
3. eodiisseo.owned-runtime.enabled=true. 제공처 data_source는 DISASTER/active 및 위 endpoint_key와 일치해야 한다.
   이번 migration은 제공처를 자동 등록하지 않는다.
4. 전역 eodiisseo.disaster.collection.enabled=true와 owner=owned-disaster-development를 명시한다.
   이는 실행 주체 확인 값이며 타 담당자 수집기와 중복 실행되지 않음을 운영자가 먼저 확인해야 한다.
   DB 잠금은 같은 canonical protocol을 따르는 실행만 배제할 수 있다.
5. 제공처별 prefix: eodiisseo.disaster.collection.earthquake / heavy-rain / wildfire-messages.
   각 prefix에 enabled, source-id, initial-from, key를 비밀설정으로 지정한다.
   key는 URL 인코딩 전 원본 값이다. 모든 제공처는 기본 비활성이다.
   문자 대량 조회 계약이 없는 상태에서는 wildfire-messages 운영 수집을 활성화하지 않는다.
   누락/잘못된 설정은 정상 수집으로 계산되지 않는다. 최초 기동 전 등록/설정을 검증해야 한다.
6. 실제 설치 모델을 확인한 뒤 기존 EODIISSEO_AI_OLLAMA_ENABLED, BASE_URL, MODEL, ALLOWED_HOSTS,
   REQUEST_TIMEOUT과 CONNECT_TIMEOUT을 설정한다. verifyInstalledModel()과 실제 /api/chat 검증은 별도 승인된 개발 환경에서 수행한다.
   eodiisseo.runtime.enabled=true, summary.max-retries/retry-delay는 검토한 값으로 설정한다.
7. 행동요령은 OPS의 활성 세션으로 내부 Importer를 사용하고, 사람이 fingerprint/권리/외부전송/인젝션/만료를 검토해 승인한다.
   승인 콘솔 또는 운영 CLI 연결은 별도 작업이다. 자동 승인은 없다.
8. 기존 HTTP 활성화 조건·인증 필터·OwnedHttpSecurityGuard를 통과한 뒤에만 기존 owned-http를 명시 활성화한다.

## 수정·신규 파일 전체 (18개, 이 보고서 포함)

기준 경로 backend/:
- src/main/java/com/disasterinfo/eodiisseo/disaster/collection/CollectionFailure.java (신규)
- src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialHttp.java (신규)
- src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClient.java (신규)
- src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialRecordNormalizer.java (신규)
- src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialCollectionService.java (신규)
- src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialCollectionScheduler.java (신규)
- src/main/java/com/disasterinfo/eodiisseo/disaster/collection/INTEGRATION_REPORT.md (신규)
- src/main/java/com/disasterinfo/eodiisseo/disaster/adapter/DisasterOperationalAdapters.java
- src/main/java/com/disasterinfo/eodiisseo/disaster/service/OwnedRuntimeSchemaGuard.java
- src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/OfficialGuidanceImporter.java (신규)
- src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/DatabaseResourceApproval.java
- src/main/java/com/disasterinfo/eodiisseo/ai/ollama/OllamaChatClient.java
- src/main/resources/db/owned-runtime/V2026110303__owned_collection_and_resource_import.sql (신규)
- src/test/java/com/disasterinfo/eodiisseo/dashboard/DashboardAssignedSecurityReadinessTest.java (기존 본인 담당 HTTP 테스트)
- src/test/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClientTest.java (신규)
- src/test/java/com/disasterinfo/eodiisseo/disaster/OwnedRuntimeSeparationIntegrationTest.java
- src/test/java/com/disasterinfo/eodiisseo/ai/resource/OfficialGuidanceImporterTest.java (신규)
- src/test/java/com/disasterinfo/eodiisseo/ai/ollama/OllamaReadinessTest.java (신규)

auth/collection/area/location/group/common/기존 integration/공용 migration/빌드/프론트엔드 변경 0건.
AuthService.java는 HEAD와 동일, CollectionRecordWriter.java와 DomainReadAutoConfiguration.java는 대상 기준대로 추가하지 않았다.
staged 파일 0건. 기존 작업 및 실행 서버를 보존했다.
