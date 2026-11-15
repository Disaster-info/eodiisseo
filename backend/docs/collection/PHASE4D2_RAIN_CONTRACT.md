# 4D-2 호우특보 계약·수집 정합성 검증

## 최종 운영 판정

**KMA_RAIN_CODES/getPwnCd 운영 수집은 계속 차단한다.** 모순된 페이지를 안전하게 거부하는 구현과 합성 DB 회귀를 보완했으나, 제공처의 totalCount=0 처리 계약·snapshot/증분 보장·전체 시점별 행정구역 매핑은 확보되지 않았다. 실제 침수 피해 사건을 확정하는 자료도 아니다.

기준: `feature/collection-unification`, `75f0a66` 및 기존 4C·4D-1 커밋 보존. 검토일 2026-11-15.
실제 데이터 API GET 0건. 인증키/.env 미조회. 공개 공식 설명 HTML·가이드 ZIP 및 공개 DB 항목 정의만 검토했다. Collector/Scheduler·운영 DB·API 승인·Push/PR/배포 변경 없음.

## 공식 근거와 실제 관측의 구분

- [P: 기상청 기상특보 조회서비스](https://www.data.go.kr/data/15000415/openapi.do), 페이지 수정일 2026-06-01.
- [G: 현재 공개 가이드 ZIP](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003648415&fileDetailSn=1), `기상청21_기상특보 조회서비스_오픈API활용가이드_260601.zip`.
- G SHA-256: `6cb5f8e52df4709d5e7daff129948a33620bf708b9f9abd405480e07b0b32ca4`. 11월 4일 보고서에 기록된 버전·해시와 동일하다.
- [H: 기상특보 DB 항목 정의](https://apihub.kma.go.kr/static/html/attach/wrn_table.html)는 Hub DB 스키마 설명이며 getPwnCd의 응답·코드 사전과 자동으로 동일시하지 않는다.
- [기존 PHASE7 보고서](../../../PHASE7_RAIN_CONTRACT_REPORT.md): 2026-11-04, 2026-09-26 하루, page 1/2·rows 5·warningType 2·stnId 108. HTTP 200/resultCode 00, **각 5행/totalCount 0**를 기록했다. 시작·해제 시각의 조건부 0도 관측했다.
- 당시 폐기용 DB의 원문 저장/byte 일치/롤백은 보고됐지만 DB는 폐기됐다. 이번에는 당시 원문을 다시 확보하거나 실제 행을 재생하지 않았다. 합성 응답은 반례 테스트이며 실측 원문 복원본이 아니다.
- [PHASE5 기록](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/PHASE5_VALIDATION_REPORT.md)은 오래된 조회의 99 응답에서 현재 기준 60일 안내를 관측했다. 현재의 포함 경계·timezone·지연 수정 재조회 계약으로 확대하지 않는다. G에서 모든 운영 경계를 충분히 확인하지 못했다.

## getPwnCd 계약 대조

| 항목 | 문서에서 확인 | 기존 관측·코드 및 미확정 사항 |
|---|---|---|
| URL/인증 | `https://apis.data.go.kr/1360000/WthrWrnInfoService/getPwnCd`, serviceKey | 고정 HTTPS·키 1회 encoding 유지. Hub/다른 operation fallback 없음 |
| 필터 | fromTmFc/toTmFc는 발표일, pageNo/numOfRows/dataType, stnId/areaCode/warningType | 기존 호우 요청 warningType=2/stnId=108 유지. 가이드 예시의 warninType 철자는 표의 warningType과 다르며 자동 대체하지 않음 |
| totalCount | 전체 결과 수 | 0+행 존재의 예외·원인·대체 수치 규칙은 확인 못함. 행 수로 치환 금지 |
| pageNo/numOfRows | 페이지 번호/한 페이지 결과 수 | 정렬·동일 snapshot·offset 안정성·재조회 변동·마지막 빈 페이지 보장 없음 |
| 빈·짧은 페이지 | 응답 형태 설명 | 파싱 성공과 전체 범위 완전성을 분리. 짧은 비최종 페이지를 완료로 추정하지 않음 |
| 날짜 | 입력 YYYYMMDD, 발표시각 별도 | 코드의 구간 폭<=3일은 로컬 예산이다. 자료 연령 60일·날짜 포함·timezone·보존 보장과 다름. 기존 KST 해석도 공식 route별 보장으로 승격하지 않음 |
| tmSeq | 월별 발표번호 | 전역 사건 ID가 아니다. 날짜·관서·구역과 분리 불가, 월 경계 재사용을 합성 검증 |
| areaCode | 특보구역 코드 | 일반 행정구역 코드나 피해 범위가 아니다. 지역명/prefix/거리로 매핑하지 않음 |
| warnVar/warnStress | 호우 2, 강도 0/1/2 | 원천 WATCH/WARNING/MAJOR_WARNING 유지. 실제 피해·공통 심각도로 자동 추정하지 않음 |
| command | 1 발표, 2 해제, 3 연장, 6 정정, 7 변경발표, 8 변경해제 | 각 코드 의미는 확인했으나 predecessor/정정 참조·정렬·삭제/재조회 보장은 별도 미확정 |
| cancel | 0 정상, 1 취소된 특보 | 취소 대상 warning과 이전 상태 복원의 공식 계약 없음. 새 취소 자료로 임의 종료하지 않음 |
| start/end/allEnd | 발표·해제 시 조건부 시각 | 0을 날짜/epoch로 바꾸지 않음. allEndTime으로 개별 endTime을 대체하지 않음 |

G의 getPwnCd 표에서 시작시각은 발표 시, 종료시각은 해제 시 제공됨을 확인했다. 문단 추출 번호 1655(기능), 1800~1810(번호/구역), 1823~1865(종류·강도·명령·조건부 시각·취소)는 원문 확인용이며 페이지 번호로 위장하지 않는다.

H의 WRN_CMD(예: 2 대치/3 해제)는 G의 command(2 해제/3 연장)와 다르다. 이를 섞어 정규화하지 않았다. `KMA_WARNING_LIST`도 별도 operation이며 변경·승인하지 않았다.

## 실제 구현과 호환성

- [OfficialProviderClient.decodeRainCodes](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClient.java): 기존 decoder를 재사용하는 명시적 getPwnCd 검증 profile이다. pageNo/numOfRows/totalCount/items 누락, 요청과 다른 페이지, 실제 행>totalCount, 선언 rows 모순/요청 예산 초과, JSON 중복 키·후행 문서를 거부한다.
- 기본 `decode`와 legacy `page()`는 유지한다. 기존 RainPageMetadataTest의 “0+행을 그대로 파싱 후 수집 서비스에서 거부”, “누락된 optional echo 파싱” 기대값을 바꾸지 않았다.
- [OfficialCollectionProviderPlugin](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialCollectionProviderPlugin.java)은 **KMA_RAIN_CODES만** strict profile을 호출한다. KMA_WARNING_LIST는 기존 decoder 경로다. 별도 제어 테스트로 누락 echo 호환성과 미승인 상태를 확인한다.
- 명시적 JSON/XML 빈 응답을 파싱할 수 있어도 정상 범위 완료나 Coverage를 뜻하지 않는다. emptyResult·boundedRange·stableSnapshot·incremental 승인은 별도이며 기본 Evidence는 미승인이다.
- 요청보다 작은 선언 페이지 용량은 실제 행과 모순이 없으면 관측 가능하지만, 비최종 짧은 페이지는 Coordinator에서 거부한다. 제공처의 pagination 보장이 확보됐다고 주장하지 않는다.
- [OfficialRecordNormalizer.rain](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialRecordNormalizer.java): 기존 warning이 있을 때 command=1/startTime=0을 이전 시작시각으로 채워 새 Revision을 만드는 결함을 실제 PostgreSQL에서 재현했다. **새 발표의 시작시각 누락은 기존 WARNING_PREDECESSOR_MISSING으로 거부**한다. 연장·정정의 기존 predecessor 검증과 오류 계약은 유지한다.
- [CollectionCoordinator](../../src/main/java/com/disasterinfo/eodiisseo/collection/execution/CollectionCoordinator.java) 및 [OfficialCollectionService](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialCollectionService.java)의 전체 count·중복·짧은 중간 페이지·빈 결과·소유권·quota·실패 처리는 재사용하며 변경하지 않았다.
- [OfficialCollectionProjection](../../src/main/java/com/disasterinfo/eodiisseo/disaster/projection/OfficialCollectionProjection.java)은 원문 최종 반영에서도 같은 plugin을 재호출하므로 strict 검증을 중복 구현하지 않는다.
- 기존 양성 합성 Fixture 2곳에는 요청 page/rows를 명시했다. 기존 성공/실패 기대값·timeout·생략 조건은 변경하지 않았다.
- HEAVY_RAIN의 기존 disaster 행은 특보 lifecycle 표현으로만 검증한다. 실제 침수 피해 사건을 입증하지 않으며 기존 HTTP/DB enum을 변경하거나 운영 피해 사건을 생성하지 않는다. 이 의미·canonical source와 구 Worker 직접 경로의 전환 검토도 운영 전 별도 조건이다.

## 페이지·상태·트랜잭션 검증

- 모순 total, 필수 metadata 누락, 잘못된 page, total 변동, 중복/내용 변경, 부분 HTTP 실패, 빈 결과, 짧은 중간 페이지를 합성 응답으로 검증한다.
- 부분 실패 시 실제 첫 HTTP attempt가 VALIDATED가 된 후 두 번째 응답까지 수신됐음을 확인한다. 이 상태는 전체 수집/도메인 성공이 아니다.
- 기존 10개 도메인/원문 테이블의 행 전체를 비교한다: disaster, source_disaster, source_disaster_revision, disaster_runtime_outbox, disaster_area, disaster_change, collection_raw_record, collection_raw_revision, collection_domain_cursor, collection_domain_coverage.
- 실패 run은 SUCCESS가 아니며, 실패 응답 Archive는 QUARANTINE으로 보존되고 기존 NORMAL 개수는 늘지 않는다. 원문을 current로 승격하거나 기존 Cursor/Coverage를 갱신하지 않는다.
- 중복·역순·동일 시각 상충·해제·취소 재수신·조건부 시각·잘못된 코드/지역·월 경계 tmSeq 재사용·여러 구역의 같은 발표를 기존 테스트와 확장 DB 회귀로 검증한다.
- 기존 동일 JPA/JDBC 트랜잭션·fencing·롤백·Outbox 규칙을 재사용한다. 전체 페이지가 실패한 뒤 부분 Revision/영향지역/Outbox가 남지 않는다.

## 영향지역: 운영 매핑은 미확보

공식 ZIP XLSX는 코드/명칭/시작·종료 및 REG_ID·REG_UP 등 특보구역 정보를 담는다(워크시트 각 415행, 헤더 포함). 확인한 열에는 전체 행정구역 대응 자료가 없다. REG_UP은 상위 **특보구역**이며 서비스 admin_area 부모 관계로 복사하지 않는다.

H의 REG_WRN2에는 nullable ADM_ID(행정동 10자), TM_ST/TM_ED가 정의돼 있지만 이는 데이터 전체·다대다 관계·과거 분할/통합·서비스 행정코드 정합성을 보장하는 실제 대응 자료가 아니다.

[VerifiedWarningImpactReader](../../src/main/java/com/disasterinfo/eodiisseo/disaster/adapter/VerifiedWarningImpactReader.java), [OfficialImpactAreaSynchronizer](../../src/main/java/com/disasterinfo/eodiisseo/disaster/service/OfficialImpactAreaSynchronizer.java), [DisasterAreaNormalizer](../../src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterAreaNormalizer.java)를 재사용한다. 운영 Mapping Bean이나 가짜 행정구역 매핑은 추가하지 않는다.

매핑 만료/불완전/철회/모호 코드 시 현재 Port는 UNKNOWN이다. 새 Projection은 실패·롤백하며 기존 행/Revision/Outbox/커서 및 과거 공식 매핑 행은 보존한다. 과거 행 보존을 현재 매핑 승인으로 해석하지 않는다. 매핑·UNKNOWN 코드 자체는 수정하지 않았다. area/location 팀 코드는 변경하지 않는다.

## 차단 상태와 외부 문의

기존 KMA_RAIN_CODES의 `TOTAL_COUNT_CONTRADICTION_UNRESOLVED`, `COMPLETE_TEMPORAL_AREA_MAPPING_REQUIRED`, `SNAPSHOT_INCREMENTAL_CONTRACT_REQUIRED`를 유지한다. 나머지 6개 API 승인도 변경하지 않는다.

제공기관에 확인할 항목:
1. resultCode 정상·행 존재·totalCount 0의 원인, 어떤 건수를 어떤 범위로 계산하는지, 공식 예외 계약과 수정 일정.
2. page/rows echo 필수성·서버 cap, 안정 정렬/tie-breaker, snapshot/token, 조회 중 삽입·수정·삭제 및 최종 페이지 판정.
3. 현재 60일 안내의 포함 경계·기준 timezone·보존기간·지연 발표/동일 발표 정정/취소 재조회와 무누락 증분 규칙.
4. tmSeq 월/관서별 namespace와 발표 간 공식 predecessor/정정/해제/취소 참조, 같은 시각의 수정 순서.
5. REG_ID/areaCode의 대응, 전체 ADM_ID 교차자료와 유효기간·다중지역·철회·분할/통합 이력 및 코드 체계.

## 승인 후 최소 실측 계획 — 현재 미실행

- getPwnCd 개발용 operation 승인, serviceKey 유효기간·허용 IP·현재 quota·고정 조회일·메타데이터 보관 책임자를 먼저 확인한다. 키 값은 출력·로그·명령 인자로 남기지 않는다.
- 예시 후보는 2026-11-14 하루이며 실제 승인 시 발표 존재·허용 기간을 수동 확인해 확정한다. 과거 2026-09-26을 자동 재사용하거나 날짜를 탐색하지 않는다.
- 명시 승인 후 pageNo=1/numOfRows=5/dataType=JSON/warningType=2/stnId=108 **최대 1 GET**을 먼저 관측한다. 수동 검토 및 별도 페이지 승인 범위 안에서 pageNo=2 **최대 1 GET**, 합계 최대 2 GET. 같은 페이지 retry와 자동 날짜 변경 없음.
- 403/429/5xx/timeout·모순·빈 결과는 정상 종료/Coverage로 변환하지 않는다. 후속 호출을 자동 실행하지 않는다. 2 GET으로 snapshot/증분/취소 순서를 입증했다고 판단하지 않는다.
- 기본 보관은 원문 없이 HTTP·결과코드·페이지/건수·항목 shape·해시·크기·오류 조건만 허용한다. 원문이 필요하면 별도 보관기간·접근권한·비밀정보 검출·폐기 책임 승인부터 받는다.
- EarthquakeProbe는 호우 route를 지원하지 않으므로 재사용해 호출하지 않는다. 기존 OfficialDevelopmentLiveValidationTest는 raw DB 보관을 포함하므로 현재 실행하지 않는다. 다음 단계에서 호우 전용 metadata-only 관측 도구/수동 승인 절차를 검토해야 한다.

## 초기 재현과 결과 기록

- 신규 합성 profile 재현: 14 발견 / 8 통과 / 6 실패 / 0 생략. 정상 빈 결과와 WARNING_LIST 보존 제어는 유지한다.
- 새 발표 시작시각 누락의 실제 PostgreSQL 재현: 1 발견 / 0 통과 / 1 실패. 기대 FAILED 대신 COMPLETE가 관측됐다. 기록은 수정 후 결과와 별도로 보존한다.
- 수정 후 담당 회귀: 211 발견 / 209 통과 / 0 실패 / 기존 조건부 생략 2.
- 신규 단위 15건, 신규 PostgreSQL 19건은 모두 합성 계약 검증이다. 실 API의 완전성/운영 승인 증거가 아니다.
- 기존 Collection 관련 Migration 11개(2026110201 및 1001~1006·1301~1302·1501~1502)를 포함해 **전체 SQL 23개**를 시작 시 목록·SHA-256으로 보존했다. 신규 Migration 없음.
- 실제 API 수신·운영 원문 등록·피해 사건 확정은 이번 단계에서 수행하지 않았다.

## 최종 전체 검증·보존

- `clean build` 성공, 17분 15초. **1,822 발견 / 1,800 통과 / 0 실패 / 22 생략**.
- 신규 단위 15건, 신규 실제 PostgreSQL 19건 모두 실행·통과하며 전체 집계에 포함된다.
- 기존 RainCollectionContractIntegrationTest 22건, RainPageMetadataTest 7건, 공식 Projection PostgreSQL 전체 54건, 4D-1 지진 계약 10건 통과.
- 기존 TLS 4건·명령/Fleet PostgreSQL 13건·Gateway-over-TLS 6건 포함 4C 회귀 통과.
- 생략은 기존 외부 실호출/설정 14, Disabled 5, RAG scope 2, 실제 pgvector 1. 새 생략 조건을 추가하거나 통과로 집계하지 않았다.
- 실패 재현 XML 2종(합성 profile 6실패, 시작시각 DB 1실패), 담당 회귀 및 최종 전체 결과를 서로 구분해 보존했다.
- 실제 데이터 API 호출 0, 새로운 실데이터 수신/실제 피해 사건 확정/운영 계약 승인 0. 합성 검증을 제공처 보장으로 승격하지 않는다.
- 신규 Migration 없음. Collection 11개를 포함한 전체 SQL 23개 및 비공개 catalog SHA-256 불변. `git diff --check` 통과.
- 이번 TLS 서버 2개와 TEMP 데이터·역할·인증서·credential 정리 완료. 해당 원본 디렉터리 잔존 0, 생성한 Collection 임시 DB/역할 잔존 0.
- 작업 전부터 있던 owned_* DB 1개의 이름·소유자·생성시각·접속 수는 기준선과 동일하다. 기존 데이터는 조회·수정·삭제하지 않았다. 원래 audit PostgreSQL도 유지했다.
- 폐기용 bootstrap 비밀번호의 로그 일치 0건. 실제 .env/인증키는 읽거나 수정하지 않았다.
- 검증 증빙은 로컬 `%TEMP%/eodiisseo-stage4d2-20261115`에 남기며 공식 ZIP·임시 결과는 커밋하지 않는다.
- 코드·테스트 커밋: `7373193c958505e3ccb8890c1eefd2a8def3e3f0`.
- 운영 차단 판정 유지. Push·PR·배포·실제 Worker 교체·Collector/Scheduler 활성화 없음.

## 변경 파일과 목적

| 파일 | 목적 |
|---|---|
| `disaster/collection/OfficialProviderClient.java` | 기존 parser를 재사용한 명시적 strict getPwnCd profile |
| `disaster/collection/OfficialCollectionProviderPlugin.java` | KMA_RAIN_CODES만 profile 연결; WARNING_LIST 기존 경로 보존 |
| `disaster/collection/OfficialRecordNormalizer.java` | 새 발표 시작시각 누락에 대한 predecessor 재사용 차단 |
| `disaster/collection/RainCodesContractTest.java` (test) | 15건 profile·빈 결과·WARNING_LIST 제어 단위 검증 |
| `collection/execution/CollectionProviderPluginTest.java` (test) | 양성 Rain Fixture의 요청 페이지 metadata 명시 |
| `disaster/projection/OfficialCollectionProjectionPostgresTest.java` (test) | metadata Fixture와 19건 페이지·상태·mapping 원자성 회귀 |
| `backend/docs/collection/PHASE4D2_RAIN_CONTRACT.md` | 공식 근거·기존 관측·구현·검증·보류 실측 계획 |