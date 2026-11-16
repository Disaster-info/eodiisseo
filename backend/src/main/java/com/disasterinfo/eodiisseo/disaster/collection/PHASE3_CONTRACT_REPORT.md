# 3차 공식 재난 데이터 계약 검증 보고서

확인일 2026-11-03(KST). 작업 브랜치 feature/ai-dashboard-noti, 기준 HEAD 6586216.
시작 시 작업 트리는 깨끗했고 1·2차 구현은 해당 커밋에 보존되어 있었다.
이번 변경 10개 파일은 unstaged로 남겼다. Commit/Stage/Push/Merge/PR/Reset/Stash/브랜치·Worktree 생성 없음.

## 결론

공식 문서에서 확인한 crtDt, KMA 정상 응답 예시, 호우 중대경보를 기존 구현에 반영했다.
지진 사건 연결·행정구역 전체 대응·실시간 산불 사건 API·누락 없는 문자 증분 계약은 확보하지 못했다.
해당 Port에 임시 운영 구현체를 추가하지 않았으며 UNLINKED/UNKNOWN과 자동 증분 차단을 유지한다.
문서/정적 첨부만 열람했다. 실제 공공 API 데이터, 인증키, Ollama, FCM, 운영 DB/.env/서버는 사용·변경하지 않았다.
테스트 성공은 실데이터·운영 검증 성공을 의미하지 않는다.

## A 공식 데이터 계약 매트릭스

모든 문서 확인일은 2026-11-03이다. “높음”은 문서상 명시된 사실에 대한 확신이며 실호출 검증 수준이 아니다.

| 대상/기관 | API·데이터셋·문서 | 사건/발표 식별 | 수정·종료/지역 | 증분/확신 | 구현·운영 판정 |
|---|---|---|---|---|---|
| 지진/기상청 | S1 EqkInfoService/getEqkMsg | stnId 지점, tmFc 발표시각, tmSeq 월별 발표 일련번호, cnt 참조번호. 사건 ID와 참조 대상/수명은 미확인 | fcTp=5 국내 재통보는 명시됨. cor 수정사항. 지진 취소·종료/사건 간 관계는 미확인. loc·lat/lon은 진앙이며 영향지역이 아님 | fromTmFc/toTmFc 발표일 조회 및 pageNo/numOfRows. 필드 의미 높음, 사건 연결 미확인 | 기존 저장/Port 재사용. 새 사건 자동 연결 불가, EARTHQUAKE_UNLINKED 유지 |
| 호우/기상청 | S2 getPwnCd, S3 구역 코드표 | areaCode 특보구역 코드, stnId/tmFc/tmSeq 발표 정보. 공식 사건 ID와 별개 | warnVar=2, warnStress=0/1/2, command=1/2/3/6/7/8, cancel=0/1. startTime/endTime/allEndTime. 구역 코드 유효기간·상위 특보구역은 확인됨, 행정코드 전체 대응 미확인 | fromTmFc/toTmFc, 페이지. 코드/단계 높음; 행정 crosswalk·공통 severity 미확인 | 원천 단계 2 반영. 영향지역 Port는 UNKNOWN, 운영 매핑 구현체 추가 안 함 |
| 산불/산림청·MOIS | S4 국가산불정보시스템, S5 통계 API, S6 재난문자 | S4 화면의 사건 고유키/API 이용계약 미확인. S5 출력에 검증된 사건 ID·후속 관계 미확보. S6 SN은 문자 일련번호 | S4 안내상 발생일시·진화일시·진행상태·대응단계 표시. S5 통계의 발생/진화 일자와 주소는 실시간 상태 계약이 아님. 문자 수신지역은 영향지역이 아님 | S5 searchStDt/searchEdDt는 발생시작일 기준. S4 공개 사건 API·갱신/SLA·과거 범위 미확인 | 통계/위험도/화면 자료로 사건 자동 등록하지 않음. WILDFIRE_MESSAGE_UNLINKED 유지 |
| 문자/MOIS | S6 DSSP-IF-00247 | SN 일련번호. 사건 ID 아님. CRT_DT 생성, REG_YMD 등록, MDFCN_YMD 수정, RCPTN_RGN_ID/NAME 수신지역 | 생성·등록·수정 필드 구분은 명시됨. 시간대/문자열 포맷/수정 순서·삭제 계약은 미확인 | crtDt STRING 조회시작일자 YYYYMMDD 및 pageNo/numOfRows/returnType/rgnNm 확인. 포함 범위·수정 replay·정렬/snapshot/과거 한계 미확인 | crtDt 요청 구현. finite snapshot만, 날짜 Coverage/Cursor 전진 및 Scheduler 자동 증분은 계속 차단 |

### S1 지진

[공공데이터포털 지진 설명서](https://www.data.go.kr/data/15000420/openapi.do)
기관 기상청, 포털 수정일 2026-08-18.
첨부 “기상청29_지진정보_조회서비스_오픈API활용가이드.docx”, FILE_000000003808227/1.
다운로드 접근 확인을 정상 통과한 뒤 문서를 읽었다. 가이드 자체의 버전 날짜는 확정하지 않았다.
문서 표상 stnId=108 전국 고정 설명, tmFc 발표시각(분), tmEqk 진앙시(초)가 구분된다.
fcTp: 2 국외/3 국내/5 국내 재통보/10 현장경보/11 국내 조기/12 국외 조기/14 속보.
이 값들만으로 특정 원발표/사건을 찾아가는 참조키와 순서가 정해지지는 않는다.
cnt 설명은 참조번호에 그치므로 사건 ID나 상대 tmSeq로 쓰지 않았다.
tmSeq의 월별 초기화 때문에 사건 내 단조 순서로 사용하지 않는다.
cor 텍스트와 재통보 종류로 임의 취소·종료를 만들지 않는다.
기존 발표별 레코드의 ID/키/원문을 병합하거나 변경하지 않았다.

포털 표의 정상 resultCode=00와 가이드 예시의 0/NORMAL_SERVICE가 다르다.
KMA에 한해 0과 정확한 NORMAL_SERVICE 조합을 추가 허용했다. 단독 0·다른 메시지나 MOIS의 0은 성공으로 확대하지 않는다.
실제 응답에서 어느 형식을 쓰는지는 미검증이다.

### S2 호우 단계와 상태

[기상특보 설명서](https://www.data.go.kr/data/15000415/openapi.do)
기관 기상청, 포털 수정일 2026-06-01.
공개 상세기능 “특보코드조회”(oprtinSeqNo 23457)의 설명을 읽었다.
첨부 “기상청21_기상특보 조회서비스_오픈API활용가이드_260601.zip”, FILE_000000003648415/1.
포털 요약 표에는 warnStress 0/1만, 첨부 가이드에는 2=중대경보까지 있다.
그 차이를 기록하고 source_status=MAJOR_WARNING과 표시 제목 “중대경보”로 반영했다.
공통 severity 카탈로그에 대응된다는 근거는 없으므로 official_severity_id를 임의 배정하거나 SEVERITY_INCREASE를 만들지 않는다.
command는 발표/해제/연장/정정/변경발표/변경해제, cancel은 정상/취소를 구분한다.
취소된 발표의 대상·사건 연결이 미확인인 경우 기존 raw 보류를 유지한다.
단계 변경 시 이전 “주의보” 제목이 남는 오류도 수정했다.

### S3 특보구역과 행정구역의 차이

동일 ZIP의 “기상청21_기상특보 조회서비스_오픈API활용가이드_특보구역코드안내(260601).xlsx”를 확인했다.
Sheet1 B2:E2는 areaCode/구역명/시작일/종료일이고, Sheet2 A1:G1은 REG_ID/TM_ST/TM_ED/REG_SP/REG_UP/REG_KO/REG_NAME이다.
시작·종료·상위 특보구역은 있으나 이 파일에서 서비스 행정코드·전체 포함 대응 열은 확보하지 못했다.
구역명 또는 L/S 코드 접두부로 행정코드를 생성하지 않는다. REG_UP도 행정코드가 아니다.
날짜 열의 경계 포함 방식·종료 sentinel 의미·표준시/이력 개정 정책은 추가 확인이 필요하다.

[KMA 예·특보 구역 설명](https://apihub.kma.go.kr/apiList.do?seqApi=10&seqApiSub=321)과
[AWS↔특보구역 정적 DB 설명](https://apihub.kma.go.kr/static/html/attach/wrn_aws2_table.html)도 확인했다.
getWrnZoneCd는 warningAreaCode/korName, AWS 자료는 STN_ID/FCT_ID/WRN_ID 등을 다룬다.
이는 행정구역 전체 포함 대응표의 대체물이 아니다.
[특보 설명](https://apihub.kma.go.kr/apiList.do?seqApi=10&seqApiSub=288)의 REG_ID/REG_UP/TM_ST/TM_ED도 같은 제약이 있다.
API 예시 URL은 읽기만 했고 호출하지 않았다.

운영 OfficialWarningAreaMappingPort는 추가하지 않았다.
기존 계약 테스트는 합성 전체 대응이 있을 때만 VERIFIED이며, 부분/상충 미해결/만료/철회/이름 유사성은 UNKNOWN이다.
해제 후에도 유효했던 합성 mapping을 과거 asOf로 읽을 수 있고 activeIncident=false와 코드 집합이 유지되는 것을 확인했다.
이 검증은 공식 행정 crosswalk 확보나 영구 매핑 이력 저장의 완료 판정이 아니다.
실제 버전 충돌 선택과 과거 매핑 제공 책임은 향후 검증된 제공자에 있으며, 현재 Port가 이를 추정하지 않는다.

### S4~S5 산불

[국가산불정보시스템](https://fd.forest.go.kr/ffas/gis/main.do)의 최초 공개 HTML/안내만 열람했다.
발생·진화 시각과 진행상태를 표시한다는 안내는 확인했으나 실제 사건 데이터/내부 호출은 조회하지 않았다.
웹 화면 존재로 공개 API·사건 ID·재배포 허가를 추정하지 않는다.

[산림청 개방목록](https://www.forest.go.kr/kfsweb/opda/dataMng/selectPblicDataList.do?mn=NKFS_06_08_02&tabs=5)이 안내한
[산불발생통계(대국민포털)](https://www.data.go.kr/data/3070842/openapi.do)를 확인했다.
기관 산림청, 포털 수정일 2026-08-04. getfirestatsservice는 searchStDt/searchEdDt와 발생/진화 일시·주소·원인·피해면적을 제공한다.
본문은 통계 집계 서비스로 설명하지만 메타데이터에는 갱신 “실시간” 표기도 있어, 이 문구만으로 실시간 사건 상태 보장을 인정하지 않는다.
개발/운영 자동승인·개발 트래픽 10,000·이용제한 없음 표시는 이 통계 서비스의 안내에 한정한다.
공식 고유 사건키·상태 변경/취소·문자 SN 대응·실시간 사건 API 접근/재배포 계약은 별도 필요하다.
산불 통계·위험지수·위성 탐지 후보를 확정 사건으로 사용하지 않았다.

### S6 재난문자와 crtDt 정정

[MOIS 데이터 설명](https://www.safetydata.go.kr/disaster-data/view?dataSn=228)의 공개 API 탭이 사용하는
[문서 표 HTML](https://www.safetydata.go.kr/disaster-data/apiDataTable?dataSn=228)을 읽었다.
이는 실제 V2 데이터 API나 미리보기 데이터 조회가 아니다.
기관 행정안전부/재난정보통신과, 문서상 갱신주기 1분·데이터 갱신 일자 2026-11-03.
이는 데이터 일자를 문서에서 확인한 것이며 실제 자료를 조회한 결과가 아니다.
[공개 이용가이드](https://www.safetydata.go.kr/userGuide/search/openApi)는 활용신청→승인→인증키 절차를 안내한다.
포털 일반 이용 안내는 공공기관 데이터 공공누리 3유형을 설명하므로 실제 데이터별 이용조건을 재확인해야 한다.

crtDt STRING/조회시작일자(YYYYMMDD), rgnNm STRING/시도·시군구명은 명시되어 있다.
이전 보고서에서 날짜 필터 확인을 놓친 부분을 정정했고 각 보고서 앞에 정정 안내를 추가했다.
다만 crtDt가 당일만인지 시작일 이후인지, >=인지 >인지, CRT_DT/REG_YMD/MDFCN_YMD 중 무엇에 적용되는지는 명시만으로 확정할 수 없다.
과거 보유 범위, 기본 정렬, 페이지 snapshot, 수정/지연 등록·삭제 재조회 기준도 확인하지 못했다.
SN은 22자리로 안내된 일련번호이나 전 기간 유일성·숫자 변환·사건 ID는 가정하지 않는다.
문서의 MDFCN_YMD는 “수정일자”이며 정렬 가능한 정확한 timestamp 포맷/시간대로 임의 파싱하지 않는다.
따라서 from 날짜를 crtDt로 전달하되 상한 날짜·정렬 파라미터를 발명하지 않았다.
문자에서는 from/to가 다른 요청을 UNSUPPORTED_END_DATE로 거절한다. 같은 날짜를 전달해도 “해당 하루만 반환”이라는 뜻은 아니다.
요청·원문 취득은 가능하지만 무누락 자동 증분은 여전히 INCREMENTAL_CONTRACT_REQUIRED로 차단한다.
Cursor/Coverage 완료 표시는 하지 않고, 응답에 들어온 날짜 밖 자료도 계약 미확인 상태에서 자의적으로 버리지 않는다.

## B 기존 구현 점검과 변경

| 기존 기능 | 점검 분류 |
|---|---|
| DisasterService, OfficialDisasterQueryService | 저장/조회 구현됨. live source 등록·실데이터 연결은 미완료. 기존 ID 유지 |
| OfficialCollectionService/Scheduler/Client | 유한 수집/잠금/실패/진행일 구현됨. crtDt·정상 코드 추가 수정 완료. 문자 안정 증분 미확인 |
| OfficialRecordNormalizer | 원문/정규화 구분 구현됨. 중대경보/표시 제목 수정. 실제 영향/공통 severity 매핑 미완료 |
| VerifiedIncidentLinkPort/Service | 검증 소비·원자적 저장 구현됨. 공식 연결 규칙 미확인, 운영 resolver 없음 |
| OfficialWarningAreaMappingPort/VerifiedWarningImpactReader/DisasterImpactAreaReader | 검증 조회·UNKNOWN 구현됨. 실제 행정 대응표/운영 제공자 미완료 |
| DisasterRuntimeEvents/Worker | Outbox·영속 요약/재시작 구현됨. 실제 모델·실발송 연결 미검증/비활성 |

기존 Port/Service를 재사용했다. 새 추상화·임시 운영 Bean·자동 승인은 추가하지 않았다.
동일 snapshot 안에서 같은 SN이 두 번 보이면 내용/수정일자가 달라도 불안정 가능성으로 전체 rollback한다.
이는 제공처의 유일성 보장을 주장하는 검사가 아니라 현재 수집기의 보수적 거절 규칙이다.
SN·원문 hash를 사용한 다른 실행 간 재수집/수정 원문 보존은 유지한다.

변경 파일 10개(신규 3, 기존 7):
- main disaster/collection/OfficialProviderClient.java
- main disaster/collection/OfficialCollectionService.java
- main disaster/collection/OfficialCollectionScheduler.java
- main disaster/collection/OfficialRecordNormalizer.java
- main disaster/collection/INTEGRATION_REPORT.md (정정 안내)
- main disaster/collection/PHASE2_REPORT.md (정정 안내)
- test disaster/OwnedRuntimeSeparationIntegrationTest.java
- test disaster/collection/MoisDocumentedContractTest.java (신규)
- test disaster/collection/KmaGuideContractTest.java (신규)
- main disaster/collection/PHASE3_CONTRACT_REPORT.md (신규)

main/test Java 경로는 backend/src/main/java/com/disasterinfo/eodiisseo 및 backend/src/test/java/com/disasterinfo/eodiisseo 기준.
Migration 추가 없음. V2026110301~0304 및 공용 Migration 변경 0건.
source_status의 기존 varchar(30)에 MAJOR_WARNING을 저장하고 기존 title 컬럼을 갱신하므로 스키마 변경이 필요 없다.
공용 심각도/수집 이력/FK/발생 ID는 변경하지 않았다.
보호 영역 auth/collection/area/location/group/common/타 integration/공통 보안·빌드·프론트엔드 변경 0건.
실제 인증 필터/ACTIVE 세션/DTO·9개 Controller 계약과 기본 비활성을 유지했다.

## C 외부 확인 질문과 다음 단계

기상청 지진:
1. cnt의 정확한 참조 대상, namespace·월/연도 경계·reset·다중 참조 규칙은 무엇인가?
2. getEqkMsg가 공식 사건키를 제공하지 않는다면 승인된 사건키 공급 API와 발표 연결 필드는 무엇인가?
3. fcTp=5/정정/취소의 원발표 키와 전순서/동시 수정 구분은 무엇인가?
4. legacy 발표별 레코드를 새 사건에 연결할 검증 대조표를 제공할 수 있는가?

기상/지역 담당:
1. 특보구역과 사용하는 법정/행정코드 체계의 전체 포함 대응표 및 예외(산간/도서/부분 지역)는 어디에 있는가?
2. 분할·통합의 적용시각·종료 sentinel·경계 포함·시간대와 버전 충돌/철회·과거 이력은 어떻게 제공하는가?
3. WATCH/WARNING/MAJOR_WARNING과 공통 severity의 승인된 대응 근거가 있는가?
4. 관심지역 소유권·행정계층 비교는 기존 공개 Service로 제공해야 하며 이 모듈이 회원/위치 DB를 우회하지 않는다.

산림청/MOIS:
1. 허가된 실시간 사건 API·공식 사건 ID·발생/진화완료/취소·좌표와 영향지역·후속 버전 계약은 무엇인가?
2. 기관 간 문자 SN↔사건키 연결 근거·재배포/호출 권한·갱신 SLA·과거 보유 범위는 무엇인가?
3. crtDt의 비교 대상/경계/상한/retention·기본 정렬·stable page/snapshot은 무엇인가?
4. MDFCN_YMD의 형식·시간대/정밀도·수정/지연/삭제 자료 재조회 및 SN 수명/유일성 범위는 무엇인가?

수집 담당자는 이 답변 전 자동 증분을 켜지 않고 canonical job lock·원문 hash·유한 재조회·실행 소유권을 유지해야 한다.
DB 담당자는 향후 공식 사건키/매핑 이력의 실제 길이·복합키·보존/FK 영향을 검토한다. 이번에는 DDL 변경 없음.
실연결은 별도 승인된 개발 환경에서 제공처 활용신청·인증키, 검증 source 등록, 공식 매핑·사건 resolver,
실제 모델 설정과 회귀 검증이 모두 준비된 후 진행한다. 실제 키 값은 소스/보고서/로그에 기록하지 않는다.

## D 테스트와 검증 한계

최종 명령:
./backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain -PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase3-final-20261103

BUILD SUCCESSFUL: 전체 1,005 / 통과 994 / 실패 0 / 생략 11.
2차 988 대비 17건 추가. 집중 37건도 통과했다.
- crtDt 날짜 경계(윤일/연초/연말), 미확인 종료일 거절, 8자리 연도, KMA 요청 계약 회귀.
- 생성/등록/수정 필드·긴 SN 보존, 동일 SN의 수정 hash 보존, snapshot 재정렬/중복/수정 중 변경/총수 변동/중단·재개.
- 문자 Cursor/Coverage 비전진, 실패 시 원문/정규화/이벤트 원자성.
- 공식 재통보 종류·같은 cnt만으로 지진 병합 금지.
- 중대경보 source 단계/제목·Revision, 중복 방지, 임의 공통 severity/위험 증가 이벤트 없음.
- 합성 mapping의 발효/해제·과거 asOf/만료 경계·부분 분할/변경/철회와 UNKNOWN.
- 기존 목록·상세·Outbox·영속 작업·Ollama 대역·RAG 승인/검색/FALLBACK·9개 HTTP·인증·소유권·개인정보 회귀.

공식 사건/행정 대응이 없는 항목의 성공 사례는 기존 합성 Port 계약을 사용한 조건부 테스트다.
실제 공식 사건키·crosswalk·데이터·모델 품질을 검증했다고 보고하지 않는다.
실제 지진/산불의 운영 resolver, 문자 무누락 증분, 현재 허가된 실시간 산불 API는 미완료다.

생략 11건은 성공으로 계산하지 않았다:
Ollama 3/RAG 실모델 1/FCM OAuth 1/실제 자격증명 wiring 1/KMA 날씨 1은 실호출/실자격증명 비활성.
Docker AssignedDatabaseIntegrationTest 4는 실행 조건 미충족.
별도 loopback PostgreSQL/PostGIS 통합·0303→0304 upgrade/Flyway checksum 검증은 실행했다.
기존 0301~0304 checksum은 유지되며 운영 DB에 migration을 적용하지 않았다.

테스트 환경: 명시적 폐기용 audit cluster 127.0.0.1:55433에서 생성한 owned_<uuid> DB만 사용/제거.
임시 토큰 키와 synthetic JWT/사회인증 대역 사용. 실제 .env/서비스 계정·인증키·실행 서버 변경 없음.
모든 실호출 test 환경 플래그는 false. 실제 모델 다운로드/HTTP 등록/보안 우회 없음.
결과: C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase3-final-20261103/reports/tests/test/index.html

## 문서 확인 증거

정적 문서만 TEMP/eodiisseo-phase3-official-docs-20261103에 저장했다. 운영 corpus나 Git 데이터로 등록하지 않았다.
첨부 다운로드는 사이트의 공개 check-limit 절차를 통과했으며 CAPTCHA/로그인 우회 없음.
DOCX 표와 XLSX 셀 구조를 읽기 전용으로 검사했다. 문서 레이아웃/인쇄 QA를 수행했다는 주장은 하지 않는다.
다음 해시는 확인한 bytes를 고정하는 증거이며 기관의 전자서명이나 최신성 보장은 아니다.

- 지진 첨부 DOCX: C083416069E51333D7C8F4B3AEFC78EA66B3827F2D790AB011B689CB7F06FF63
- 특보 첨부 ZIP: 6CB5F8E52DF4709D5E7DAFF129948A33620BF708B9F9ABD405480E07B0B32CA4
- 특보 첨부 DOCX: 192AE53F23122009C8D6EA48CB256A2C11DAE1746E6F9B838F5261DE19C2CF35
- 특보 코드표 XLSX: A7CCA3779F81E1C86B9761C63F4B51FDBB6AC4917CBC41373579093A78488693
- MOIS 공개 설명서 표 HTML: 3B647D73244AB421FDD0B6FB701CBF37B46D90A31C659697400F3CEE5D207B96
- 산림청 통계 설명 HTML: 03B8B98878D0B64648902797EB1443E4516351C80BEEE2DE6D7F628D5F2D537D
