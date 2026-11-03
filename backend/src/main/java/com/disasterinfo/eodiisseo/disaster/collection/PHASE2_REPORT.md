# 재난 데이터·RAG 2차 보완 결과

2026-11-03. 작업 경로: C:/Users/dkemf/source/eodiisseo-integration-ops-latest
feature/ai-dashboard-noti / HEAD 943a19c 유지. 이전 미커밋 18개 보존·확장.
이번 보완 19개(신규 13, 기존 6), 전체 미커밋 32개. Stage/Commit/Push/Merge/PR/Reset/Stash/브랜치·Worktree 생성 없음.
1차 INTEGRATION_REPORT.md는 과거 결과로 보존하며 현재 판정은 이 보고서 기준이다.

## 구현과 운영 판정

| 항목 | 검증 결과 | 운영 선행조건 |
|---|---|---|
| 산불 | 미연결 원문 보존. 합성 검증 계약을 제공한 경우에만 목록·상세·중복·수정·종료·Revision·Outbox 연결 | 실제 사건 ID·기관·후속/상태 연결 계약과 운영 Port 미확보 |
| 지진 | DisasterService.earthquake 재사용. 발표와 사건 ID 분리, 같은 검증 사건의 후속 Revision, 중복·역순·불일치 거부 | cnt/tmSeq를 사건 ID나 사건 순서로 간주하지 않음. 실제 연결 규칙 미확보 |
| 호우 | 정확한 코드·공식 근거·전체 대응·유효기간·활성 행정코드가 검증될 때만 지역 표시. 발효·변경·해제·철회 검증 | 공식 crosswalk 원본·버전·분할/통합·유효기간 미확보. 운영 매핑 Port 없음 |
| KMA 증분 | 연속 성공 구간, 하루 중첩, 페이지 변동·rollback·재개·임대 충돌 검증 | 초기 조회일·제공 범위·트래픽·수집 실행 소유권 협의 |
| 문자 증분 | bounded snapshot만 허용. 날짜 Cursor 전진 없음. Scheduler는 INCREMENTAL_CONTRACT_REQUIRED로 자동 반복 차단 | 날짜/수정 필터 또는 안정된 cursor·정렬·snapshot 계약 필요 |
| RAG | 내부 오프라인 관리 도구, 명시적 승인·철회·대체·만료·검색 검증 | 실제 자료 0건, 자동 승인 없음. OPS의 실제 출처·권리·외부 전송·인젝션 검토 필요 |
| Ollama | 세 유형 모두 실제 HTTP Client·DB·Revision·Outbox·Worker·OllamaChatClient·저장·인증 조회 연결 | 실제 모델·품질 미검증. 응답/사건/매핑은 합성 Fixture |

**테스트 통과는 운영 가능 판정이 아니다.**
실제 공공 API/Ollama/FCM 호출, 실제 행동요령 취득·자동 승인, 운영 DB/.env/키/서버 변경은 하지 않았다.
공개 설명을 검토했지만 새 공식 사건/지역 연결 계약은 확보하지 못했다. 도구 접근 제한이나 설명 부족을 “식별자가 없다”로 단정하지 않는다.
참조: https://www.data.go.kr/data/15000420/openapi.do ,
https://www.data.go.kr/data/15000415/openapi.do ,
https://www.safetydata.go.kr/disaster-data/view?dataSn=228 .
현재의 Port 정의는 필요한 내부 소비 계약이며 공식 API 필드를 발명한 것이 아니다.

## 사건 연결

VerifiedIncidentLinkPort는 source/provider, 발표 ID, exact canonical SHA-256, 사건 ID,
공식 단조 증가 순서, 발표·발생·종료시각, 확정 상태, 공식 근거 URL·계약 버전을 검증하는 신뢰된 제공자가 필요하다.
운영용 구현체/기본 승인은 등록하지 않았다. 같은/낮은 순서의 다른 내용, 역순, 미래/불일치 근거는 보류한다.
SN은 문자 ID이고 tmSeq는 발표 번호다. 수집 순서·시간/좌표 근접·이름/문자 유사도로 사건을 만들거나 합치지 않는다.
payload에 incidentId를 넣어도 근거가 되지 않는다. 규모→심각도, 진앙/수신지역→영향지역 변환 없음.

안전성 변경: 새 지진도 계약이 없으면 EARTHQUAKE_UNLINKED, 산불은 WILDFIRE_MESSAGE_UNLINKED로 보존한다.
기존 발표별 레코드는 삭제·재키잉·자동 병합하지 않았다. legacy와 새 사건 ID의 이관·대조 계약은 별도 필요하다.
미연결 원문은 검증 계약 확보 뒤 같은 hash로 재처리할 수 있고 raw 행을 중복 생성하지 않는다.
저장·Revision·Outbox·사건 head는 같은 트랜잭션이다. 모델 장애는 공식 저장에 영향을 주지 않는다.

## 영향지역 Port

OfficialWarningAreaMappingPort.resolve(exactWarningZone, warningIssuedAt, asOf):
정확한 코드, 완전한 행정코드 집합, 근거 URL, 버전, validFrom/Until, completeCoverage를 제공해야 한다.
없음/부분/만료/위조/코드 불일치/행정코드 없음/경쟁 출처/조회 실패는 UNKNOWN이다.
DisasterImpactAreaReader.find(disasterId, asOf)는 VERIFIED/UNKNOWN, 행정코드, 활성 여부만 반환한다.
관심지역 담당자가 별도 공개 Service로 ACTIVE 회원·소유/미삭제 지역·행정계층을 확인해야 한다.
이 Port는 interest_area/member_location을 직접 조회하거나 기존 InterestAreas/Location Port를 임시 구현하지 않는다.
해제 후 activeIncident=false만으로 UNKNOWN을 UNRELATED로 바꾸면 안 된다.
호우 region은 검증된 매핑만 표시하므로 근거 미확인 기존 표시는 null/UNKNOWN이 될 수 있다.

## 증분·복구·최신성

owned_collection_coverage의 성공 KMA 구간이 coverage_start부터 연속으로 이어질 때만 completed_through가 전진한다.
뒤 구간 성공으로 중간 누락을 건너뛰지 않는다. CollectionWindow는 최대 3일/하루 중첩과 초기 날짜 경계를 유지한다.
KMA 응답의 시각/조회일 불일치, 페이지 개수 변동/중복/불완전/상한 초과는 실패로 처리한다.
실패 시 기존 데이터/진행일을 보존한다. Cursor→job lock 순서로 잠그고 구 실행이 successor를 덮어쓰지 못한다.
재개는 영속 상태와 기존 최대 30분 임대를 사용한다. 호출 간격·일일 예산·기본 비활성 정책은 유지한다.
문자 snapshot은 날짜 복구 성공으로 표시하지 않는다. 이전 legacy Cursor 값은 이관 시 보존되지만 새 증분 근거로 사용하지 않는다.
Scheduler.statusSnapshot()은 안전한 계약/설정/실행 코드를 제공한다.
과거 구간/날짜 계약 없는 snapshot/당일까지 연속 구간이 없는 수집은 LATEST 근거가 되지 않는다.
구버전 원문 재관측은 현재 hash와 같을 때만 현재 레코드 collected_at을 갱신한다.

## RAG 내부 관리 도구

GuidanceAdminTool.run(activeOpsBearer, Reader jsonCommand, Writer jsonOutput).
승인된 내부 개발 콘솔의 Spring Bean을 명시 호출한다. 자동 Runner/공개 HTTP/독립 서버 CLI 부트스트랩은 추가하지 않았다.
도구의 fetch 호출은 없다. 최대 2 MiB JSON 1개를 받고 토큰/raw HTML 없이 메타데이터만 출력한다.
- IMPORT: hazard, sourceUrl, title, bodyElementId, html, expiresAt. 오프라인 원문 등록, 승인 없음.
- INSPECT: resourceId. 기관/URL/제목/버전/fingerprint/확인·만료·대체·승인 상태.
- APPROVE: resourceId, expectedFingerprint, validUntil, evidenceReference,
  rightsAndExternalAiUseConfirmed=true, injectionReviewed=true 필요. ACTIVE OPS·기존 승인 검사를 유지한다.
- REVOKE: resourceId. EXPIRE: 만료 취득 bytes 정리와 철회.
- 같은 URL의 변경 원문은 IMPORT로 새 ID/버전을 발행하고 이전 승인을 철회한다.
동일 bytes 재등록으로 만료/대체 승인을 되살리지 않는다. 미승인/만료/철회/대체 자료는 검색에서 제외하고 FALLBACK 유지.
일반 행동요령 RAG와 특정 발생 Revision 요약은 별개다. RAG로 실시간 위험·발생·경로를 추정하지 않는다.
실제 출처 진위/권리/인젝션은 사람이 확인해야 한다. 토큰은 Git/로그/명령행에 넣지 않는다.
UTF-8 정적 HTML 외 문서 파서, 동일 bytes 만료 재발행 및 기존 인용 완전 삭제 정책은 미완료다.

## 선택적 Migration 영향

V2026110304__verified_incidents_and_contiguous_coverage.sql 추가. 공용 및 0301/0302/0303 파일 변경 없음.
- owned_incident_head: source+사건 PK, source_disaster UNIQUE FK/CASCADE, 현재 공식 순서·발표·hash·근거.
- owned_collection_coverage: run별 최대 3일 구간, source/window 인덱스, collection_run FK/CASCADE.
- owned_collection_cursor.coverage_start: 기존 watermark를 보존하고 시작 기준만 보수적으로 설정. 과거 성공 이력 생성 없음.
전체 owned 적용 시 업무 테이블 48개(canonical 37 + owned 11).
공유 부모 FK/수집 이력 보존 영향은 DB 담당자 검토 필요. 이력 삭제 시 미완료 구간은 재수집이 필요할 수 있다.
폐기용 DB에서 0303→Cursor Fixture→0304→watermark/실패 코드 보존→Flyway checksum validate를 검증했다.
운영 데이터 upgrade 자체는 미검증이며 운영 clean/repair/baseline 없음.

## 최종 테스트

./backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain -PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase2-final-20261103

BUILD SUCCESSFUL: 전체 988 / 통과 977 / 실패 0 / 생략 11.
1차 966 대비 22건 추가: 통합 클래스 10→20, 새 단위 테스트 12.
9개 HTTP·실제 인증 필터/ACTIVE 세션·소유권·암호화·보존·동의 회귀 유지.
수정 중 Map 제네릭 컴파일 오류는 수정 후 최종 전체 실행으로 재검증했다.

검증: 세 재난의 정상/중복/수정/실패, 미연결→재처리, 산불 종료·지진 역순,
호우 발효/변경/해제/매핑 철회, KMA 구간·페이지 변동·DB rollback·재개·임대 충돌,
문자 자동 요청 0회/날짜 전진 없음, 세 유형의 실제 SDK·DB·Ollama 대역 요약 저장·인증 조회,
한국어 합성 RAG 세 자료의 OPS 등록/명시 승인/검색/철회/대체/만료, 최신성/구버전 재관측, migration upgrade.

생략은 성공으로 계산하지 않았다:
Ollama 3 + RAG 실모델 1 + FCM OAuth 1 + 실제 자격증명 wiring 1 + KMA 날씨 1: 실호출/실자격증명 비활성.
Docker AssignedDatabaseIntegrationTest 4: 실행 조건 미충족. 별도 PostgreSQL/PostGIS 통합·upgrade는 실제 실행.
127.0.0.1:55433의 명시적 폐기용 audit cluster에서 생성한 owned_<uuid> DB만 사용/제거했다.
실제 키 대신 테스트 임시 키를 사용·정리했다. 운영용 합성 Bean/참조 데이터 등록 없음.
리포트: C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase2-final-20261103/reports/tests/test/index.html

## 미완료와 협의

실제 사건 관계/기관/상태/순서, legacy 이관, 공식 지역 crosswalk/유효범위, 관심지역 소유권·행정계층,
문자 필터/cursor/정렬/snapshot, 이력 보존·실행 소유권, 실제 RAG 권리 검토와 모델 품질 검증이 필요하다.
9개 Controller/기존 DTO/보안 설정은 이번에 추가 활성화·확장하지 않았다.
다른 담당자의 운영 auth/collection/area/location/group/common/integration/공용 migration/빌드/프론트 변경 0건.
1차 Dashboard 보안 테스트 변경은 보존했으며 이번에는 추가 수정하지 않았다.

## 이번 보완 파일 (19개)

신규 13개:

- disaster/port/VerifiedIncidentLinkPort.java
- disaster/service/VerifiedIncidentService.java
- disaster/port/OfficialWarningAreaMappingPort.java
- disaster/port/DisasterImpactAreaReader.java
- disaster/adapter/VerifiedWarningImpactReader.java
- disaster/collection/CollectionWindow.java
- ai/resource/adapter/GuidanceAdminTool.java
- db/owned-runtime/V2026110304__verified_incidents_and_contiguous_coverage.sql
- disaster/VerifiedIncidentServiceTest.java (test)
- disaster/VerifiedWarningImpactReaderTest.java (test)
- disaster/collection/CollectionWindowTest.java (test)
- disaster/collection/OfficialCollectionSchedulerTest.java (test)
- disaster/collection/PHASE2_REPORT.md

기존 보완 6개:

- disaster/collection/OfficialRecordNormalizer.java
- disaster/collection/OfficialCollectionService.java
- disaster/collection/OfficialCollectionScheduler.java
- disaster/service/OfficialDisasterQueryService.java
- disaster/service/OwnedRuntimeSchemaGuard.java
- disaster/OwnedRuntimeSeparationIntegrationTest.java (test)

## 전체 미커밋 파일 목록 (이전 작업 포함)

- backend/src/main/java/com/disasterinfo/eodiisseo/ai/ollama/OllamaChatClient.java
- backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/DatabaseResourceApproval.java
- backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/GuidanceAdminTool.java
- backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/OfficialGuidanceImporter.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/adapter/DisasterOperationalAdapters.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/adapter/VerifiedWarningImpactReader.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/CollectionFailure.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/CollectionWindow.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/INTEGRATION_REPORT.md
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialCollectionScheduler.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialCollectionService.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialHttp.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClient.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialRecordNormalizer.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/PHASE2_REPORT.md
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/port/DisasterImpactAreaReader.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/port/OfficialWarningAreaMappingPort.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/port/VerifiedIncidentLinkPort.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/OfficialDisasterQueryService.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/OwnedRuntimeSchemaGuard.java
- backend/src/main/java/com/disasterinfo/eodiisseo/disaster/service/VerifiedIncidentService.java
- backend/src/main/resources/db/owned-runtime/V2026110303__owned_collection_and_resource_import.sql
- backend/src/main/resources/db/owned-runtime/V2026110304__verified_incidents_and_contiguous_coverage.sql
- backend/src/test/java/com/disasterinfo/eodiisseo/ai/ollama/OllamaReadinessTest.java
- backend/src/test/java/com/disasterinfo/eodiisseo/ai/resource/OfficialGuidanceImporterTest.java
- backend/src/test/java/com/disasterinfo/eodiisseo/dashboard/DashboardAssignedSecurityReadinessTest.java
- backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/CollectionWindowTest.java
- backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialCollectionSchedulerTest.java
- backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClientTest.java
- backend/src/test/java/com/disasterinfo/eodiisseo/disaster/OwnedRuntimeSeparationIntegrationTest.java
- backend/src/test/java/com/disasterinfo/eodiisseo/disaster/VerifiedIncidentServiceTest.java
- backend/src/test/java/com/disasterinfo/eodiisseo/disaster/VerifiedWarningImpactReaderTest.java
