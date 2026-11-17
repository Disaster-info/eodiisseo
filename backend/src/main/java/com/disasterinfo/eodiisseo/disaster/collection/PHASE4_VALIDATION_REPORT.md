# 4차 공식 재난 사건 확정·개발 환경 검증 보고서

확인일: 2026-11-04(KST). 작업 디렉터리: C:/Users/dkemf/source/eodiisseo-integration-ops-latest.
기준 HEAD: 6e9b70b617598b04e5154cb57abe726fef602894, 브랜치 feature/ai-dashboard-noti.
시작 시 작업 트리는 깨끗했다. 모든 이번 변경은 unstaged이며 Commit/Stage/Push/Merge/PR/Reset/Stash/브랜치·Worktree 생성은 수행하지 않았다.

## 결론

공식 재난문자 API의 실제 응답 수신과 폐기용 DB 원문 저장은 성공했다.
지진·호우는 제한된 과거 날짜 조회에서 HTTP 200, 제공처 코드 03으로 데이터 해독 검증이 실패했다.
실제 재난 사건 확정, 정규화, Revision·Outbox 생성 성공은 어느 제공처에서도 주장하지 않는다.
기존 합성 Fixture의 사건 연결 성공과 실제 공식 사건 계약 확보는 별개다.

특보 DB 스키마에는 REG_ID와 ADM_ID가 함께 정의되어 있다. 그러나 전체 대응 행, 서비스 지역코드와의 호환성, 시점별 완전성을 확보하지 못했다.
운영 OfficialWarningAreaMappingPort와 VerifiedIncidentLinkPort 구현을 추가하지 않았다.
DB enum·HTTP 계약·기존 Migration은 변경하지 않았다.

## 1. 인증키·이용승인과 요청 범위

사용자가 세 제공처 모두 개발용이며 활용승인이 완료되었다고 확인했다.
기존 .env의 KMA_PUBLIC_SERVICE_KEY, SAFETYDATA_DISASTER_MESSAGE_KEY를 읽기 전용으로 사용했다.
KMA_HUB_AUTH_KEY의 존재는 확인했지만 이번 데이터 GET에는 사용하지 않았다.
키 값, 요청 URL의 인증 쿼리, 실제 원문·문자 내용·개별 SN은 Git·보고서·테스트 로그에 포함하지 않았다.
.env와 인증키 파일을 수정하지 않았다. 특화 산불 패키지에 대한 별도 승인·키 확보는 확인되지 않았다.

| 제공처 | 고정 데이터 endpoint | 날짜 조건 | 실제 GET 수 | 결과 |
|---|---|---|---:|---|
| 기상청 지진 | EqkInfoService/getEqkMsg | 2026-11-03 하루 | 2 | 모두 HTTP 200. 최초 테스트 PROVIDER_CODE, 마지막 숫자 진단 03 |
| 기상청 호우 | WthrWrnInfoService/getPwnCd | 2026-11-03 하루, warningType=2, stnId=108 | 2 | 모두 HTTP 200. 최초 테스트 PROVIDER_CODE, 마지막 숫자 진단 03 |
| 행정안전부 재난문자 | V2/api/DSSP-IF-00247 | crtDt=20261102, crtDt=20261103 각각 한 번 | 2 | 둘 다 정상 해독. 각 5건, 응답 totalCount는 각각 33·18 |
| KMA Hub 매핑·산불 사건 API | 운영 endpoint 계약 미확보 | 해당 없음 | 0 | 문서 조사만 수행 |

데이터 GET 총 6회, 모두 pageNo=1, numOfRows=5. 전체 페이지·전체 기간 수집은 하지 않았다.
Java 검증은 기존 OfficialHttp·OfficialProviderClient의 타임아웃·속도 제한을 재사용했다.
KMA 마지막 진단은 숫자 코드만 확인하는 단발 GET으로, 15초 타임아웃·자동 리다이렉트 없음·1초 간격을 적용했다.
기존 .env URL을 임의로 따라가지 않고 고정 공식 HTTPS endpoint를 사용했다.

03은 성공 코드로 완화하지 않았다. 해당 요청의 데이터 부재 응답을 실제 발표의 필드 호환성이나 사건 부재의 증명으로 간주하지 않는다.
다른 날짜를 무한 탐색하거나 키를 바꿔가며 반복 호출하지 않았다.

## 2. 실제 응답 → 저장 → 사건 판정

| 검증 단계 | 지진 | 호우 | 재난문자(산불 전용 API 아님) |
|---|---|---|---|
| 공식 서버 HTTP 응답 | 200 | 200 | 200, 2회 |
| 정상 데이터 구조 해독 | 실패: PROVIDER_CODE/03 | 실패: PROVIDER_CODE/03 | 성공: 각 5행 |
| 폐기용 DB 원문 저장 | 첫 오류 응답 1페이지 | 첫 오류 응답 1페이지 | 정상 응답 2페이지 |
| 공식 발표 식별 | 실제 발표 행 미확보 | 실제 특보 행 미확보 | 공식 문자 SN 필드 확인 |
| 공식 사건 확정 | 미검증, EARTHQUAKE_UNLINKED 유지 | 실제 사건 판정 보류 | 사건 ID 계약 없음, UNLINKED 유지 |
| 정규화·Revision·Outbox | 생성하지 않음 | 생성하지 않음 | 생성하지 않음 |
| 재난 목록 조회 | 실데이터 사건 노출 검증 불가 | 동일 | 미연결 원문이 사건 목록에 노출되지 않음을 검증 |
| AI 요약 실연계 | 실행 안 함 | 실행 안 함 | 실행 안 함 |

직접 작성한 선택적 개발 테스트는 Spring 애플리케이션·Scheduler·Worker를 기동하지 않는다.
127.0.0.1:55433의 명시적 폐기용 PostgreSQL에서 owned_<UUID> DB를 생성하고 기존 Migration만 적용했다.
collection_run과 owned_collection_page에 원문을 저장한 뒤 테스트 종료 시 해당 생성 DB를 삭제했다.
총 4개의 첫 검증 응답 페이지(오류 2·정상 2)가 저장되었고 운영 DB에는 저장하지 않았다.
부분 표본은 collection_run에 FAILED / DEVELOPMENT_PARTIAL_PROBE로 기록하며, 완전 수집 성공·Coverage·Cursor 진전으로 위장하지 않는다.
마지막 KMA 숫자 진단 2개 응답은 메모리에서만 확인했다. 실제 원문은 Git Fixture·보고서로 반출하지 않았다.

재난문자에서 확인된 키:
SN, CRT_DT, REG_YMD, MDFCN_YMD, MSG_CN, DST_SE_ID, DST_SE_NM, EMRG_STEP_ID, EMRG_STEP_NM, RCPTN_RGN_ID, RCPTN_RGN_NM.
이들은 문자의 식별·작성/등록/수정·수신지역 필드이며 산불 사건 ID·확정 영향지역으로 승격하지 않았다.
선택된 행이 산불 문자라는 별도 주장도 하지 않는다.

서로 다른 시작일의 첫 페이지 표본 사이에서 동일 SN 1건이 중복 관측되었다.
표본의 CRT_DT는 검증 도구의 엄격한 ISO 날짜 부분 파서로 판정되지 않아 각 5건 모두 created_unknown이었다.
실제 값·형식·시간대·날짜 경계는 검증되지 않았다. 파서를 추정으로 완화하지 않았다.
동일 SN의 수정 여부, MDFCN_YMD 재조회, 정렬, 늦은 등록, 조회 중 페이지 이동은 이번 두 응답으로 검증할 수 없다.
INCREMENTAL_CONTRACT_REQUIRED와 MOIS Coverage/Cursor 진전 금지를 유지한다.

## 3. 사건 확정 규칙

다음 분류는 이 보고서의 논리적 기준이며 DB enum이나 HTTP DTO 상태를 새로 만들지 않았다.

| 분류 | 필요한 근거 | 이번 실검증 |
|---|---|---|
| RAW | 공식 응답 원문 보존 | 오류 응답 2페이지, 정상 문자 2페이지 저장 후 폐기 |
| OFFICIAL_BULLETIN | 공식기관 발표 및 발표 식별자 확인 | 정상 재난문자 SN 확인. 별도 재난 사건 확정은 아님 |
| VERIFIED_INCIDENT | 공식 사건 ID 또는 검증된 발표 간 연결 계약 | 실제 데이터에서 확보 못함 |
| UNKNOWN / UNLINKED | 사건·영향지역 근거 부족 | 지진·산불 미연결, 영향지역 UNKNOWN 정책 유지 |

### 지진

[기상청 공식 지진 API 문서](https://apihub.kma.go.kr/apiList.do?seqApi=7)는 tmFc·tmEqk·tmSeq·cnt·fcTp·cor 및 위치·규모 필드를 설명한다.
tmSeq는 발표 일련번호, cnt는 참조번호라는 설명만으로 사건 ID나 선행 발표의 정확한 외래키를 확정할 수 없다.
실제 조회에서 발표 행이 없었으므로 이들 필드의 실응답 타입·값, 후속·정정 발표의 관계는 미검증이다.
발생시각·좌표·규모의 유사성으로 병합하지 않으며 EARTHQUAKE_UNLINKED를 유지한다.
공식기관에 사건 ID의 유일성 범위, cnt의 참조 대상, 연도별 발표번호 초기화, 정정·취소·역순 도착 규칙을 요청해야 한다.
기존 VerifiedIncidentLinkPort의 source/provider/announcement/contentSha256/incidentId/officialSequence/issuedAt/state/evidenceUrl/contractVersion을 실제 근거로 채울 계약이 선행돼야 한다.

### 호우

[공식 기상특보 DB 스키마](https://apihub.kma.go.kr/static/html/attach/wrn_table.html)의 REG_WRN2에
REG_ID(VARCHAR2(8)), TM_ED(PK의 일부), TM_ST, REG_UP, ADM_ID(VARCHAR2(10), 행정동코드, NULL 허용)가 정의되어 있다.
따라서 문서상 행정동 관계가 존재하지 않는다는 결론은 부정확하다. 이번 확인 범위는 관계의 스키마 정의까지다.
실제 전체 행, 복수 행정동 포함 방식, 해상·산지·부분 구역, 누락 구역, 행정동/법정동 코드 구분과 우리 서비스 코드 일치는 확인되지 않았다.
TM_ST/TM_ED 필드 존재만으로 모든 변경 이력 제공·경계 포함·과거 조회가 보장되는 것도 아니다.
REG_UP는 상위 특보구역이며 행정구역 코드로 사용하지 않는다. 표의 WRN_CMD를 getPwnCd command와 혼용하지 않는다.
특보 발효·변경·해제 자체를 확인하는 계약과 실제 피해 사건 확정은 구분한다.

요청 사항: REG_ID→전체 ADM_ID 집합의 공식 배포 API/파일, 코드 표준·버전, 전체 포함 보장, 유효기간 경계·분할/통합 이력, 과거 조회·철회 규칙.
지역 담당자는 자신의 Service를 통해 정확한 코드 체계·소유권 검증·시점 조회 계약을 제공해야 한다.
이를 확보하기 전에는 기존 OfficialWarningAreaMappingPort.resolve(exactWarningZone, warningIssuedAt, asOf)의
completeCoverage·evidenceUrl·version·validFrom/Until을 채우는 운영 구현을 추가하지 않는다.

### 산불

[행정안전부 내비게이션 고도화 사례](https://www.safetydata.go.kr/disaster-data/specializedService/detail?num=4)는
산불 등 위험정보 패키지를 내비게이션사에 API 연계·제공하는 사례와 JSON/MultiPolygon 패키지를 설명한다.
이는 이용 가능한 공개 사건 API의 endpoint·사건 ID·갱신 계약을 확보했다는 뜻이 아니다.
[특화서비스 이용신청 안내](https://www.safetydata.go.kr/disaster-data/specializedService/detail?num=5)는 심의와 별도 키 발급을 설명하고
산불예방·내비게이션 항목을 추후 공개 예정으로 표시한다. 일반 재난문자 활용승인을 특화 패키지 승인으로 확대하지 않는다.
산림청 국가산불정보시스템과 기존 산불 통계 자료의 확인 내용은 3차 보고서를 유지하며, 통계·문자만으로 새 사건을 생성하지 않는다.

제공기관에 실제 신청 가능 데이터셋·접근 자격·개발 키, 공식 사건 ID, 발생/진행/진화완료 상태와 정정/취소 순서,
발생위치와 영향지역, 문자 SN과 사건 ID의 대응, 갱신 SLA·과거 조회, 저장·재배포 조건을 요청해야 한다.
접근 계약이 없으므로 실시간 산불 자동 등록은 보류한다.

## 4. 구현 변경과 기존 기능 영향

- OfficialProviderClient: 명시적 1~100행 요청 overload 추가. 기존 page 메서드는 100행 기본값 유지.
  0·음수·100초과는 네트워크 호출 전에 ROW_LIMIT. 제공처가 요청 상한을 넘긴 응답도 거부.
  고정 endpoint, 인증키 인코딩, 정상/오류 코드, 날짜·페이지 상한, 기존 타임아웃·rate limit은 유지.
- OfficialProviderClientTest: 기본 100행/명시적 5행, 잘못된 상한 4사례, 응답 초과, 코드03 실패 차단의 7개 실행 사례 추가.
- OfficialDevelopmentLiveValidationTest: 기본 비활성, 개발용·API 승인·과거 날짜·폐기 DB 명시 조건.
  제공처당 최대 2회/5행, 원문 임시 저장, 비확정 사건 미노출, 비밀 값 비출력.
- 이 보고서: 실제 호출/저장/사건 확정/합성 검증과 보류 사항을 구분.

인증·소유권·기존 Controller/Service/Repository·DTO·RAG 승인·개인정보 동의·FCM 정책은 변경하지 않았다.
기존 지진/호우/산불의 합성 수집→정규화→Revision→Outbox→Ollama 대역→요약 저장→조회 테스트를 재사용한다.
실제 Ollama·외부 AI·FCM은 호출하지 않았고, 실제 RAG 운영자료 가져오기·자동 승인도 하지 않았다.
Scheduler의 기본 비활성 및 MOIS 증분 차단은 유지했다.
공식 발표만 별도 정보 화면에 표시하는 기능은 제안 사항이며 Controller·응답 계약을 추가하지 않았다.

## 5. 테스트 결과

### 전체 회귀·빌드 (실호출 모두 비활성)

명령: backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain
-PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase4-final-20261104

BUILD SUCCESSFUL, 2분 39초. 총 1,015건 = 통과 1,001 / 실패 0 / 오류 0 / 생략 14.
컴파일 경고(unchecked/deprecated)는 있었으나 빌드 실패는 없었다.
폐기용 로컬 PostgreSQL 통합 테스트를 실제 실행했고 기존 운영 서버를 재기동하지 않았다.

주요 실행 결과:
- OwnedRuntimeSeparationIntegrationTest 29건 통과:
  사건 미연결·공식 근거를 대역으로 공급한 연결·중복·정정/후속·역순·종료,
  호우 발효/변경/해제·이력 매핑 UNKNOWN, Cursor/페이지 변경/롤백·재시작,
  지진·호우·조건부 산불의 HTTP 대역→DB→Revision→Outbox→Ollama 대역→요약 저장→조회,
  RAG 명시적 승인·변경 시 이전 승인 철회·관리 도구 권한 검증을 포함한다.
  이는 공식 계약을 가정한 합성 검증이며 실제 사건 연결 계약 확보의 증거가 아니다.
- OfficialProviderClientTest 34건 통과(기존 27 + 신규 7).
- VerifiedIncidentServiceTest 2건, VerifiedWarningImpactReaderTest 6건 통과.
- 기존 인증·수집·지역 및 AI·Dashboard·Notification 회귀도 전체 작업에 포함했다.

생략 14건은 성공 건수에 포함하지 않았다:
- 실제 Ollama 3건, 실제 RAG 1건: 실모델/운영 자료 검증 범위 밖.
- AssignedDatabaseIntegrationTest 4건: Docker 미사용 환경의 기존 Testcontainers 조건.
  별도 로컬 폐기용 DB 통합 테스트 통과로 이 생략을 대체 성공 처리하지 않는다.
- FCM OAuth 1건, 기기 자격증명 파일 기반 Spring wiring 1건: 이번 작업에서는 FCM 실검증과 실제 자격증명 주입을 하지 않음.
- 실제 날씨 API 1건: 이번 재난 검증의 대상이 아니며 live flag 비활성.
- 새 재난 실검증 3건: 전체 회귀에서는 의도적으로 비활성. 아래 별도 실행 결과로 보고.

### 별도 실제 공식 API 검증

명령: 같은 Gradle test에 --tests '*OfficialDevelopmentLiveValidationTest',
개발용 확인·제공처별 승인·과거 날짜·폐기 DB 조건을 명시하고 실행했다.
출력: C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase4-live-20261104/test-results/test

총 3건 = 통과 1(재난문자) / 실패 2(지진·호우) / 생략 0.
따라서 이 실제 API 테스트 실행은 Gradle 실패이며 전체 실환경 검증 성공으로 표시하지 않는다.
실패 이유: HTTP 200 뒤 기존 정상 코드 계약을 만족하지 않아 PROVIDER_CODE.
추가 단발 숫자 진단에서 각 03 확인. 오류를 성공 코드로 바꾸거나 사건·Cursor를 생성하지 않았다.
정상 문자 2페이지와 오류 2페이지의 SQL 저장은 성공했고, 생성 DB는 종료 후 폐기했다.
마지막 KMA 숫자 진단 GET 2회는 JUnit 통과 건수에 포함하지 않았다.

### 정적·소유권 검증

git diff --check 통과. HEAD와 브랜치 유지, staged 0개.
변경 파일은 아래 4개뿐이며 다른 담당자 기존 파일·공통 설정·Migration은 diff가 없다.
자동 수집 활성화, 실제 AI·FCM·운영 DB 호출은 수행하지 않았다.

## 6. 수정 파일·소유권·후속 작업

이번 변경 4개 파일:
1. backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClient.java
2. backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/PHASE4_VALIDATION_REPORT.md (신규)
3. backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClientTest.java
4. backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialDevelopmentLiveValidationTest.java (신규)

타 담당자 기존 파일 변경 없음. Migration·빌드 설정 변경 없음.
운영 DB·실제 .env·키·실행 서버 변경 없음. 현재 브랜치·HEAD 유지, staged 변경 0개.

다음 단계:
1. 제공기관에 실제 발표가 존재하는 소규모 과거 일자와 사건 연결 명세를 확인한 뒤 별도 제한 검증한다.
   이번 03 응답을 피하려고 광범위 자동 탐색하지 않았다.
2. KMA 공식 전체 행정동 대응 데이터·시점별 계약과 지역 담당자 코드 표준을 확보한다.
3. 산불 사건 패키지의 별도 이용승인·개발 키·사건 생명주기·재배포 조건을 확보한다.
4. MOIS 날짜 포맷·시간대·포함 범위·수정 재조회·순서/snapshot·늦은 등록 계약을 확인한다.
5. 확보한 공식 사건 연결 근거로만 실제 정규화/Revision/Outbox/목록·상세를 재검증한다.
   실제 모델 품질·운영 RAG 승인·FCM 실발송은 별도의 후속 검증으로 남긴다.
