# 4D-1 지진 API 계약·정규화 검증

## 판정과 검증 경계

**운영 수집 불가.** Portal/Hub 요청·응답의 일부 문서 계약과 합성 검증은 확보했지만 공식 사건 연결, snapshot/증분 보장 및 실제 접근 검증은 미완료다. `CollectionOfficialAdmissionCatalog`의 7개 API 차단과 `PreparedCollectionRuntime.collect()` 차단을 유지한다.

검토일: 2026-11-15. 기준: `feature/collection-unification`, `3af6aca`, 이전 `383e415` 보존.
실제 데이터 API 요청은 0건이다. 인증키/.env를 읽거나 출력하지 않았다. 공개 문서 HTML·공통 첨부 스크립트·DOCX만 조회했다. 문서 조회를 데이터 API 성공으로 집계하지 않는다. 운영 DB·Scheduler·서버·API 승인 artifact는 변경하지 않는다.

## 공식 근거와 최신성

- [P: 공공데이터포털 지진정보](https://www.data.go.kr/data/15000420/openapi.do): 확인 당시 페이지 수정일 2026-08-18.
- [H: KMA Hub 지진/화산](https://apihub.kma.go.kr/apiList.do?seqApi=7): 3.2 통보문과 3.1 목록, typ01 최근/기간 지진정보는 서로 다른 operation이다.
- [G: P에 현재 연결된 활용가이드](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003808227&fileDetailSn=1): `기상청29_지진정보_조회서비스_오픈API활용가이드.docx`.
- G의 SHA-256: `c083416069e51333d7c8f4b3aefc78ea66b3827f2d790ab011b689cb7f06ff63`.
- G에서 확인한 날짜는 서비스 시작 2019-12-20이다. 별도의 최신 개정 버전·변경 이력은 확인하지 못했다. 페이지 수정일을 가이드 개정일로 간주하지 않는다. 원문 문서는 Git fixture나 RAG로 등록하지 않는다.

## Portal / Hub 독립 계약

| 항목 | Portal | Hub | 검증 수준·운영 조건 |
|---|---|---|---|
| 고정 Endpoint | `https://apis.data.go.kr/1360000/EqkInfoService/getEqkMsg` | `https://apihub.kma.go.kr/api/typ02/openApi/EqkInfoService/getEqkMsg` | P/G, H 3.2 문서 및 Fake URL 검증. 실제 GET 없음 |
| 인증 | `serviceKey` | `authKey` | 키·이용신청·IP·잔여량은 route마다 별도 확인 필요. fallback 없음 |
| 요청 | `pageNo`, `numOfRows`, `dataType`, `fromTmFc`, `toTmFc` | 같은 이름, 별도 계약 | 날짜는 발표일 조회. Probe는 명시적 단일 날짜·형식·페이지·건수만 허용 |
| 파라미터 선택성 | P/G의 페이지·건수·키 필수, 형식·날짜 옵션 | H는 인자 설명 제공 | Probe는 공식 옵션도 안전 정책상 모두 명시하도록 요구 |
| 범위 | G: 현재일 기준 최근 3일 자료 | 3.2의 독립 보존 경계는 미확인 | H 개요의 2001년~현재를 3.2의 제공 범위로 승격하지 않음 |
| 숫자 제한 | 페이지·건수 필드와 예시는 문서화 | 필드 설명 확인 | 코드 rows<=100, page<=100, total<=10000, 조회 구간<=3일은 로컬 예산이며 제공처 보장이 아님 |
| 결과 | P의 `00`, G 예시 `0/NORMAL_SERVICE` | 같은 필드, 별도 접근 계약 | 기존 구분 유지. `03/3 + NODATA_ERROR`는 관측이며 Coverage가 아님. Hub 오류표 동등성 미확정 |
| 페이지 | pageNo/numOfRows/totalCount/items | 같은 필드 설명 | totalCount는 전체 결과 수. 모순 거부. 정렬·snapshot·이동 중 변동·삭제 안정성 미확정 |
| 시각 | tmFc: 발표, tmEqk: 발생 | 같은 필드 의미 | G tmFc 분 단위 예시, tmEqk 초 단위. 필드 크기 표와 예시의 정밀도 차이 존재. 시간대·날짜 경계·tmMsc 적용 계약 추가 확인 |
| 번호 | tmSeq: 월별 발표 일련번호, cnt: 참조번호 | 발표 일련번호·참조번호 | 사건 ID 및 공식 단조 sequence가 아님. cnt의 참조 namespace·수명·연결 규칙 미확인 |
| 상태 | G fcTp 2/3/5/10/11/12/14 설명, cor 수정사항 | fcTp/cor 필드 설명 | 재통보/수정 문구를 취소·종료 또는 사건 연결 근거로 쓰지 않음 |
| 위치 | lat/lon: 진앙 위·경도, loc: 진앙 위치 텍스트 | 같은 의미 | stnId는 관측/발표 지점 코드이며 행정구역 코드가 아님. 진앙을 공식 영향지역으로 승격하지 않음 |
| 규모·진도 | mt와 inT 별도 | 동일 의미 설명 | 규모를 공통 심각도로 추정하지 않음. 피해/대피구역 판단 불가 |

G 근거 위치: 데이터 갱신/제공 범위, 지진통보문 출력항목, 오류코드 첨부. 임시 XML 문단 추출 번호 99·350(최근 3일), 245(지점), 251~257(종류), 276~281(월별 번호·참조)로 재확인했다. 이 번호는 문서의 페이지 번호로 위장하지 않는다.

## 기존 관측과 미확정 원인

[PHASE4](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/PHASE4_VALIDATION_REPORT.md), [PHASE5](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/PHASE5_VALIDATION_REPORT.md)를 과거 기록으로만 참조한다.

- Portal 과거 기록: HTTP 200 및 03, 별도 과거 날짜에서 99. 03의 원문 resultMsg가 보존되지 않은 기록은 정상 빈 결과로 확정할 수 없다.
- Hub 과거 기록: getEqkMsg HTTP 403. 현재 승인 범위, 키 종류, IP, 서비스 정책 또는 다른 원인 중 어느 것인지는 확인되지 않았다.
- 과거 조회의 99를 인증 실패로 확정하지 않으며, Portal 키를 Hub에 보내거나 다른 operation으로 자동 전환하지 않는다.
- 이번에는 위 관측을 재실행하지 않았다. 정상 지진 행 수신·실제 공식 사건 저장을 성공으로 보고하지 않는다.

## 코드 대조·수정

- [OfficialProviderClient.decode](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClient.java): 지진에서 실제 항목 수가 totalCount보다 큰 모순을 거부한다. 지진에도 선언된 numOfRows와 수신 항목 수의 기존 검사를 적용한다. JSON 중복 키 및 후행 문서는 지진 전용 strict mapper로 거부한다. 다른 제공처의 parsing 계약은 유지한다.
- Client의 from/to 최대 3일 구간 폭 검사는 현재일 기준 제공 자료의 연령 검사가 아니다. 오래된 Cursor의 조회를 정상화하거나 자동 건너뛰지 않았다. route별 기준 timezone·포함 경계가 확정된 후 자료 연령 guard와 복구 정책을 별도 설계해야 한다.
- `totalCount=0`을 무시하거나 수신 행 수로 대체하지 않는다. 기존 JSON/XML 빈 결과 및 짧은 정상 페이지 수용은 유지하며, 수용 자체를 전체 수집 증거로 승격하지 않는다.
- 가이드의 필수/옵션 표와 내부 사건 identity 요건은 동일하지 않다. stnId/tmSeq 누락을 108/임의 번호로 채우지 않는다. loc 누락의 기존 fallback도 이번에 변경하지 않았다. 운영 전 필수 필드 전체와 조건부 통보 유형을 별도 검증해야 한다.
- 기존 파서의 누락 pageNo/numOfRows 허용과 기대 페이지 번호 부여는 이번에 변경하지 않았다. 운영 승인 전에 필수 메타데이터 누락 검증을 legacy/Fake 호환과 분리해 명시해야 한다. 이 관용 동작을 공식 계약으로 주장하지 않는다.
- [OfficialCollectionProviderPlugin](../../src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialCollectionProviderPlugin.java): 별도 contract와 고정 route, no-network decoder 및 범위 검사를 재사용한다. 플러그인에는 중복 구현을 추가하지 않았다.
- [DisasterService.saveEarthquakeRevision](../../src/main/java/com/disasterinfo/eodiisseo/disaster/service/DisasterService.java): loc의 비문자열 객체·배열·숫자를 저장 전에 거부한다. 좌표·발생시각 기존 검사와 실제 저장 결과 반환을 유지한다. 주소 문자열을 행정구역/영향지역으로 해석하지 않는다.
- [VerifiedIncidentService.normalize](../../src/main/java/com/disasterinfo/eodiisseo/disaster/service/VerifiedIncidentService.java): 검증 Port의 source/announcement/hash/순서/근거가 있어야 사건을 연결한다. 실제 신규 Revision이 저장된 경우에만 head·Outbox를 변경하는 구조와 Advisory Lock을 유지한다.
- `OfficialRecordNormalizer.time()`과 `DisasterService`는 현재 Asia/Seoul로 해석한다. 이 구현 가정을 가이드에 명시된 route별 timezone 보장으로 취급하지 않는다. 날짜 경계·시간대의 공식 확인과 실제 응답 대조가 필요하다.
- `tmSeq`와 `VerifiedIncidentLinkPort.officialSequence`는 별개다. 새 테스트의 명시적 sequence는 합성 Port 계약이며 KMA 연결 규칙을 입증하지 않는다.
- 지진 ENDED/취소 상태의 운영 계약은 없으며 기존 OCCURRED 검증에서 거부한다. 재통보와 cor/cnt만으로 사건·취소를 만들지 않는다.
- [CollectionCoordinator](../../src/main/java/com/disasterinfo/eodiisseo/collection/execution/CollectionCoordinator.java): 승인 근거 없는 결과가 Coverage로 전진하지 않는 기존 phase·트랜잭션·QUARANTINE 보존을 재사용한다.
- Portal/Hub가 parser/provider enum을 공유하더라도 승인·quota·target을 합치지 않는다. 정규화 source_url이 공통 EARTHQUAKE endpoint(Portal)를 사용하는 점은 실제 Hub 원문 route 증거와 별도로 검토해야 한다. Projection receipt의 contract/Archive를 실제 출처로 추적하며 운영 전 route별 source_url·canonical source 계약 확정이 필요하다.

기존 `collection/service/CollectionRecordWriter`의 직접 `DisasterService.earthquake()` 호출 경로는 보존했다. 신규 Projection의 검증 Port·Outbox 계약과 동일하다고 주장하지 않는다. 이번에는 구 Worker 교체/활성화를 하지 않았으며, 운영 전환 전 해당 경로의 drain·재시작 방지·권한 차단과 canonical source 정책을 별도로 검증해야 한다.

## 항목별 증거와 완료 기준

| 항목 | 현재 상태 | 코드·테스트 | 추가 증거/통과 기준 | blocker |
|---|---|---|---|---|
| route·인증 분리 | 문서+Fixture 확인 | EarthquakeApiProbeTest, KmaGuideContractTest | 각 API 승인과 허용 IP, 키 유효성 실측 | HUB_ACCESS_CONTRACT_UNVERIFIED |
| 정상/오류 구조 | Fixture 검증, 실환경 미검증 | OfficialProviderClientTest, EarthquakeResponseContractTest | route별 정상/오류의 메타데이터와 필드 형태 | 정상 행 실측 없음 |
| 모순된 건수·페이지 크기 | 구현·Fixture 검증 | EarthquakeResponseContractTest | 모순은 FAILED/격리, SUCCESS·대체 count 없음 | snapshot/필수 metadata 검증 별도 |
| 빈 결과·짧은 페이지 | parser 호환 유지 | 기존 KmaGuideContractTest 및 새 빈 결과 회귀 | 제공처의 종료·완전성 계약과 재현 응답 필요 | SNAPSHOT_INCREMENTAL_CONTRACT_REQUIRED |
| 월별 발표번호/참조 | 의미 일부 문서 확인 | OfficialCollectionProjectionPostgresTest | 사건키 namespace·cnt 대상·후속 순서의 공식 규칙 | OFFICIAL_INCIDENT_REFERENCE_REQUIRED |
| 정정·취소 | 합성 정정/거부 검증 | 같은 통합 테스트 및 OwnedRuntimeSeparationIntegrationTest | 공식 취소 코드·대상 사건·철회/재조회 순서 | 운영 상태 연결 미확정 |
| 잘못된 시간/좌표/위치 | 코드·합성 DB 회귀 | 기존 OwnedRuntime/좌표 검사 + 새 loc shape 회귀 | 실제 field 형식·시간대·subsecond 계약 | 실제 응답 호환 미검증 |
| Revision/Outbox/롤백 | 합성 PostgreSQL 검증 | OfficialCollectionProjectionPostgresTest | 실제 검증 사건키를 가진 응답으로 추후 별도 확인 | 실제 사건 확정 없음 |
| Cursor/Coverage 불변 | 합성 DB 검증 | 실패 전후 행 전체 및 건수 비교 | 운영 증분 계약 승인 전 자동 전진 없음 | 무누락 증분 미확정 |
| Probe CHECK/예산/비노출 | 기존 Fake 회귀 | EarthquakeApiProbeTest, EarthquakeProbeSetupIntegrationTest | 제공처별 별도 수동 승인 후에만 실행 | 현재 실행 승인 없음 |

## 최소 실측 계획 — 실행 보류

별도 명시 승인 전에는 어떤 데이터 API도 호출하지 않는다. 현재 키를 탐색하거나 CHECK 승인 파일을 실제 승인으로 생성하지 않는다.

1. Portal/Hub 각각 operation 이용승인, 개발키 만료, 허용 IP, 잔여량, 단일 날짜와 메타데이터 접근·보관 책임자를 확인한다. 키 값은 보고서/명령/로그에 포함하지 않는다.
2. 기존 Probe 정책상 승인 실행일의 KST 전일 한 날짜를 명시한다. 이는 도구의 보수적 정책이며 제공처 timezone/경계 보장이 아니다. 날짜가 달라지면 CHECK에서 거부하고 자동 수정하지 않는다.
3. Portal pageNo=1, numOfRows=1, dataType=JSON으로 최대 1 GET. Hub도 별도 승인으로 동일 규모 최대 1 GET. 합계 최대 2 GET. timeout 15초, redirect/fallback/retry/날짜 탐색 없음.
4. 기존 CLI에서 `--check`를 먼저 수행한다. 실제 credential supplier와 Sender를 만들지 않고 승인 계획·expiry·단일 사용 조건을 검증한다. `--execute-approved`는 다음 작업의 명시 승인 후에만 허용한다.
5. 원문 출력/보관 없이 route·날짜·HTTP/content-type·결과코드·page/rows/total·항목 수·필드 형태·해시/크기·오류 조건만 기록한다. 인증된 전체 URL은 남기지 않는다.
6. HTTP 정상, 제공처 정상, 실제 행 수신, 사건 확정은 네 별도 판정이다. 403/429/5xx/timeout/빈 결과/구조 오류는 재시도하지 않는다. 예산은 소비된 채 유지한다.
7. 2 GET으로 pagination·정정·취소·무누락 증분을 검증했다고 판단하지 않는다. 페이지 이동과 알려진 정정 발표는 공식 사례·범위·추가 횟수를 정한 후 별도 승인한다. 일부러 잘못된 키로 오류를 만들지 않는다.

## 제공기관 문의와 운영 승인 해제 조건

- 통보문 전용 사건 ID가 있는가? 없으면 제공 API와 tmSeq/cnt의 namespace·참조 대상·월/연도 reset·후속/정정 연결을 어떻게 증명하는가?
- 취소/철회의 기계 판독 코드, 대상 사건, 수정 순서, 삭제/재조회 및 지연 발표 계약은 무엇인가?
- Portal 최근 3일의 포함 경계·기준 timezone 및 Hub 3.2의 독립 보존기간은 무엇인가?
- 페이지 정렬 tie-breaker, stable snapshot/token, totalCount 변동, 삭제·지연 수정의 재수집 및 증분 종료 계약은 무엇인가?
- tmFc 정밀도와 tmMsc 단위/결합, 진앙 좌표 기준 및 loc 문자열 구조는 무엇인가? 진앙과 별개 공식 영향지역 자료가 있는가?
- route별 오류표/HTTP403 원인 확인 절차, 이용신청·IP·quota 범위와 재배포 조건은 무엇인가?

위 계약, 실제 소규모 접근 검증, route별 canonical source/출처 증거 및 운영 API 승인 artifact가 모두 갖춰져야 Catalog 검토가 가능하다. 4C의 Fleet/OS 격리/credential·Archive 정책 차단도 별개로 남는다. 이번 단계에서 승인을 해제하거나 운영 Collector를 활성화하지 않는다.

## 검증 결과

초기 합성 재현: 8 발견 / 2 통과 / 6 실패 / 0 생략. 실패는 Portal/Hub의 모순 total·declared rows·모호 JSON 각각에서 확인됐다. 기존 빈 응답 형식의 의미를 바꾸는 제안은 적용하지 않았다.
담당 회귀: 177 발견 / 175 통과 / 0 실패 / 기존 조건부 생략 2. 원문·키·실제 데이터 API 접근 없음. 최종 XML 및 Cursor/Coverage 행 전체 검증은 전체 clean build 집계에 포함한다.
최종 전체 `clean build`: **1,788 발견 / 1,766 통과 / 0 실패 / 22 생략**, 16분 24초.
- 신규 계약 단위 10건 및 PostgreSQL 정규화 회귀 6건 모두 실행·통과.
- Probe Fake/격리 검증 35건 통과. 공식 Projection PostgreSQL 전체 35건 통과.
- 기존 4C-3 TLS 4건, 4C-4 명령/Fleet PostgreSQL 13건, Gateway-over-TLS 6건 통과.
- 기존 생략 22건(외부 실호출/설정 14, Disabled 5, RAG scope 2, 실제 pgvector 1)을 성공으로 집계하지 않는다. 기존 생략 조건·timeout·기대값을 완화하지 않았다.
- 최초 6건의 의도된 결함 재현 실패와 수정 후 전체 성공의 XML/로그는 별도로 보존했다.
- 실제 지진 데이터 수신·공식 사건 확정·실 API 호환성 성공 건수는 0이다. Fixture/DB 테스트 성공과 구분한다.
- 신규 Migration 없음. 기존 Migration 10개 및 비공개 PHASE3_1A catalog 해시 불변. `git diff --check` 통과.
- 이번 생성한 폐기용 TLS 서버 2개와 데이터 디렉터리·인증서·credential 삭제, 테스트 역할 잔존 0. 원래 127.0.0.1:55433 서버는 유지했다.
- 더 넓은 DB 점검에서 작업 전인 2026-11-15 15:12 생성된 owned_* DB 1개(접속 0)가 발견됐다. 이번 실행 소유가 아니므로 데이터 조회·수정·삭제하지 않고 보존했다.
- 폐기용 bootstrap 비밀번호의 로그 일치 0건. 운영 인증키/.env 미접근.
- 운영 승인 7종 차단, 기존 정규화·Revision·Outbox 및 4C 보호 정책 유지. 실제 Collector/Scheduler 전환·Push·PR·배포 없음.

검증 증빙은 로컬 `%TEMP%/eodiisseo-stage4d1-20261115`에 보존한다. 원문 공식 문서와 임시 증빙은 커밋 대상이 아니다.