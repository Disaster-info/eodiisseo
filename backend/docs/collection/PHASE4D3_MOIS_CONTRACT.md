# 4D-3 긴급재난문자·산불정보 계약 검증

검토일: 2026-11-15. 시작 HEAD: a26257a65745b4e6d2a895b67449427eeb6b1adf. 브랜치: feature/collection-unification.

## 결론과 검증 경계

MOIS_MESSAGES(DSSP-IF-00247)와 MOIS_WILDFIRE(DSSP-IF-10346)는 독립 계약·Source·target이다. 동일 지역·시각·문구·식별자 문자열로 병합하지 않는다. 이번 단계는 공개 설명·항목 정의 조회와 합성 응답/폐기 PostgreSQL 검증이다. 실제 데이터 API, 미리보기, 샘플 다운로드, 인증 API 호출은 0건이다. 실제 원문 수신·공식 사건 확정·운영 승인은 수행하지 않았다.

재난문자는 원문 보존 경로를 재사용할 수 있으나 무누락 증분과 사건 연결은 미확정이다. 산불정보는 사건 ID·상태·순서·증분 계약이 충분하지 않아 운영 Adapter를 계속 비활성화한다. 기존 7개 API 승인 차단, PreparedCollectionRuntime.collect() 차단, Scheduler 기본 비활성, Gateway·Barrier·Replay Journal·Fleet Fence·Archive HOLD를 유지한다. 신규 Migration은 없다.

## 공식 자료 및 증거 수준

- [긴급재난문자 설명](https://safetydata.go.kr/disaster-data/view?dataSn=228), [공개 API 항목 정의](https://www.safetydata.go.kr/disaster-data/apiDataTable?dataSn=228).
- [산불정보 설명](https://safetydata.go.kr/disaster-data/view?dataSn=882), [공개 API 항목 정의](https://www.safetydata.go.kr/disaster-data/apiDataTable?dataSn=882).
- 공개 문서 HTML만 조회했다. apiDataTable은 설명용 HTML이며 /V2/api 데이터 요청이 아니다. 문서 표의 Y/N 및 길이만으로 모든 응답 필드의 wire type·필수성을 확정하지 않았다.
- 설명 페이지는 회원가입·활용신청을 요구하고 제공기관에 따른 공공누리 제3/4유형을 안내한다. 이를 별도의 AI 가공·재배포·개인정보 처리 허가로 해석하지 않는다.
- 로컬 조회 문서의 갱신주기 표시는 문자 1분, 산불 5분이다. 표시된 자료 갱신일은 각각 2026-11-13, 2026-11-14였다. 이는 전달 지연 상한·snapshot·완전성 보장이 아니다.
- 공개 항목 HTML SHA-256: 문자 `5b61b105a7311d880588bee57ea689b4e3dfab8f08150f8a61cf9fab38eb997e`, 산불 `54dc48ac213a60ea78733ecfb42e1e932bf27572a975284e75e687076572671a`. 동적 문서의 조회 시점 증거이며 계약 승인 fingerprint가 아니다.
- PHASE3~7 및 이전 검증 보고서의 슬래시 CRT_DT, 소수부 REG_YMD/MDFCN_YMD, 서로 다른 날짜의 동일 SN은 과거 관측 기록이다. 이번에 실제 응답을 재수신하거나 폐기된 원문을 복원하지 않았다. 테스트의 동일 모양 문자열은 합성 Fixture다.

## API별 비교

| 구분 | MOIS_MESSAGES | MOIS_WILDFIRE |
|---|---|---|
| route | /V2/api/DSSP-IF-00247 | /V2/api/DSSP-IF-10346 |
| 문서상 인증 | serviceKey, 해당 데이터 활용신청 필요 | serviceKey, 별도 데이터 활용신청 필요 |
| 날짜 요청 항목 | crtDt(조회 시작일자, YYYYMMDD) | startDt(조회 시작일자, YYYYMMDD) |
| 페이지 요청 | pageNo, numOfRows, returnType(JSON/XML) | pageNo, numOfRows, returnType(JSON/XML) |
| 식별 후보 | SN | FRSTFR_INFO_ID |
| 기존 source 별칭 | SAFETYDATA_DISASTER_MESSAGE / MOIS_DISASTER_MESSAGES | SAFETYDATA_WILDFIRE |
| 새 플러그인 실행 | 명시적 테스트 조립만; 운영 승인 차단 | executable=false, HTTP 이전 거부 |
| 증분·사건 연결 | 미확정 | 미확정 |

같은 플랫폼 키가 두 dataset의 이용승인·할당량 공유를 입증하지 않는다. contract_key·target·quota_scope를 같은 기관이라는 이유로 합치지 않는다. Registry의 재난문자 별칭만 동일 논리 target으로 유지한다. 공용 PublicCollectionClient/기존 Worker의 실행 경로는 이번에 교체하지 않았다.

## 긴급재난문자 계약

### 확인된 항목과 미확정 의미

SN은 문서의 일련번호 필드다. 전역 유일성·재사용 금지·발송기관 namespace·재발송 관계는 명시적으로 확보되지 않았다. 코드의 source+SN은 기존 원문 관측 키이며 공식 사건 ID가 아니다.

CRT_DT/REG_YMD/MDFCN_YMD는 각각 생성·등록·수정 관련 필드지만 시간대·정밀도·단조 순서·재조회 보장은 미확정이다. 슬래시·소수부 관측을 근거로 임의 KST 변환을 추가하지 않았다. MSG_CN은 원문이며 DST_SE/EMRG_STEP 분류가 사건 검증을 대신하지 않는다. RCPTN_RGN은 수신 대상 표현으로 실제 피해·영향지역 또는 행정구역 매핑 증거가 아니다.

crtDt를 정확히 하루의 닫힌 snapshot으로 해석할 근거가 부족하다. 기존 from==to 제한은 제한된 요청을 위한 로컬 정책이지 제공처의 날짜 종료 경계 보장이 아니다. 지연 등록·예전 문자의 수정·철회 재조회, 안정 정렬, 조회 중 삽입·삭제, 마지막 페이지, page/rows echo 및 totalCount 예외 계약은 미확정이다.

공개 오류표는 요청/접근/키/쿼터 오류와 XML gateway 오류 형식을 설명한다. serviceKey 만료·허용 IP·dataset별 승인 상태는 키를 읽지 않고는 확인할 수 없으며 이번에는 확인하지 않았다. 오류를 정상 빈 결과로 변환하지 않는다.

### 발견한 결함과 보완

1. 새 MOIS_MESSAGES 플러그인이 일반 parser를 사용하여 중복 JSON 키/후행 JSON 및 실제 행 수가 totalCount를 초과하는 모순을 허용할 수 있었다. 기존 STRICT_JSON을 재사용하는 decodeMessages()를 추가했다.
2. 명시적으로 반환된 numOfRows가 0이거나 요청 한도를 넘으면 거부한다. pageNo가 반환되면 요청과 일치해야 한다. 공개 계약이 불명확한 echo 필드를 새 필수 항목으로 만들지는 않았다. 누락 echo를 받아들여도 증분 승인·완전성은 별도로 필요하다.
3. SN의 Object/List/boolean/소수 값을 문자열화해 안정 식별자로 사용하는 문제를 차단했다. 정확한 정수·문자열 및 선행 0은 보존한다. 제어문자·누락은 거부한다. 이 검증은 허위 식별자 방지이며 공식 SN의 형식·유일성 보장이 아니다.
4. 이전 공용/대피소 decoder의 정책을 일괄 강화하지 않고 새 MOIS_MESSAGES 계약에서만 strict profile을 사용한다. 기존 원문 수집 경로는 잘못된 SN도 INVALID:<hash>/INVALID_FACTS로 격리 보존한다.

### 원문·수정·증분 결과

기존 OfficialCollectionService는 동일 source/SN/hash 중복을 제거하고 다른 hash의 원문 버전을 owned_collection_item에 보존한다. MDFCN_YMD가 과거여도 원문 버전은 남지만 공식 수정 순서를 판정하지 않는다. 같은 유한 페이지 묶음의 동일 SN 반복/내용 변경은 PAGING_CHANGED로 차단한다. 유효하지 않은 날짜도 RAW 문자열로 남을 수 있으며 사건 정규화의 승인 증거는 아니다.

새 Coordinator는 QUARANTINE HTTP Archive를 별도 보존한다. 모순·누락·중복·페이지 이동·부분 HTTP 실패·빈 결과 계약 또는 증분 계약 미확정 시 NORMAL 승격, current raw/revision, 사건, Outbox, Coverage, Cursor를 생성하지 않는다. 기존 raw-only 경로 역시 RAW_ARCHIVED_CONTRACT_REQUIRED/FAILED·INCREMENTAL_CONTRACT_REQUIRED 상태를 유지한다. 원문 보존 성공을 전체 수집 성공으로 보고하지 않는다.

## 산불정보 계약

FRSTFR_INFO_ID라는 식별 후보는 존재하지만 유일성 범위·재사용·종료 후 재개·정정 시 ID 유지 계약은 미확정이다. 문서 길이 1000과 기존 사건키 180 제한도 다르므로 자르거나 임의 hash로 사건키를 만들지 않았다.

발화·신고·진화 관련 시각 필드가 있지만 시각 존재만으로 공식 상태전이·종료를 확정하지 않는다. LINK_STTS는 연계 상태이며 발생/진행/진화 상태코드가 아니다. FRST_REG_DT는 최초등록, FRST_MDFCN_DT는 최초수정 관련 표기여서 최신 수정시각·공식 단조 순서로 사용할 수 없다. NDMS 관련 필드명만으로 원천 시스템과 데이터 권위 전체가 입증되지 않는다.

LINK_MSG_ID와 LINK_TRNS_ID가 메시지 SN과 같은 식별체계라는 증거는 없다. 같은 값·지역·시각·문구도 연결 근거가 아니다. 법정동 구성 필드를 임의 결합하거나 발생/신고 위치를 영향지역·대피구역으로 승격하지 않는다. 상태코드, 취소·삭제, 종료 재조회, 페이지 정렬/snapshot, 증분 종료 조건은 제공기관 확인이 필요하다.

기존 OfficialWildfireInfoInspector를 그대로 재사용한다. 독립적 offline 검토기이며 항상 UNLINKED와 계약 gap을 반환한다. 후보 필드만 제한적으로 검토하고 담당자명·담당자 ID·주소·좌표·서술문 및 연계 메시지 ID 원문을 검토 결과에 보존하지 않는다. 신규 HTTP client·운영 Adapter·Bean·Scheduler를 만들지 않았다.

## 근거 행렬

| 검증 항목 | 공식/기존 관측 | 코드 및 합성 검증 | 현재 상태·운영 blocker |
|---|---|---|---|
| 두 operation 분리 | 서로 다른 dataset/route | CollectionContractRegistry, 플러그인, 독립 source/target PostgreSQL 테스트 | 분리 구현; 개별 실제 이용승인 미확인 |
| 메시지 SN | 일련번호 필드 | OfficialRecordNormalizer.identity, MoisMessagesContractTest | 안정 자료형 검증 완료; namespace/재사용 미확정 |
| 날짜·수정 | 과거 slash/소수부 관측 | MoisRawCollectionIntegrationTest | 원문 보존; timezone/순서/재조회 미확정 |
| 페이지 모순 | snapshot/예외 보장 없음 | decodeMessages, CollectionCoordinator, Projection PostgreSQL 테스트 | 모순 거부; 제공처 완전성 미승인 |
| 무누락 증분 | 지연/수정 replay 보장 없음 | INCREMENTAL_CONTRACT_REQUIRED, Coverage/Cursor 0 검증 | 차단 유지 |
| 메시지 사건 연결 | 공식 연결키 확보 안 됨 | VerifiedIncidentService/LinkPort, 키워드·지역 불승격 테스트 | UNLINKED/미분류 유지 |
| 산불 ID/순서/상태 | 후보 필드만 존재 | OfficialWildfireInfoInspectorTest, Adapter HTTP 0 검증 | 운영 Adapter 비활성 |
| API 간 연결 | SN↔LINK_MSG_ID 공식 계약 없음 | 동일 문자열도 사건·Outbox 0 | 자동 병합 금지 |
| TX·Archive·권한 | 제공처 계약과 별개 | 기존 4C Gateway/Fleet/Barrier·Projection rollback 테스트 | 기술 회귀와 공식 승인 구분 |

## 승인 후 최소 실측 계획 — 이번에는 미실행

각 dataset마다 개발 계정의 현재 활용승인, 키 유효기간, 허용 IP, 잔여 quota와 공유 범위, 고정 조회 시작일, 관측 metadata 보관 책임을 먼저 확인한다. 인증키·URL·원문을 명령행/로그에 출력하지 않는다.

- 문자: 승인된 고정 crtDt, pageNo=1, numOfRows=5, returnType=json으로 최대 1 GET. 날짜·시각 문자열/항목 shape/HTTP·제공처 결과/건수 metadata만 관측한다.
- 산불: 별도 이용·개인정보 보관 계약 승인 후 고정 startDt, pageNo=1, numOfRows=5, returnType=json으로 별도 최대 1 GET. 미승인 상태에서는 요청하지 않는다.
- 합계 최대 2 GET도 별도 명시 승인 후에만 허용한다. 자동 retry·다음 페이지·날짜 탐색·대량 수집·DB 등록은 없다. 첫 관측으로 snapshot/증분/상태전이를 입증하지 않는다.
- EarthquakeProbe는 MOIS route를 지원하지 않으므로 route를 바꿔 재사용하지 않는다. 기존 live validation은 raw DB 저장을 포함해 이번 승인 범위와 다르다. 다음 단계에서 metadata-only 관측 도구 또는 수동 승인 절차를 별도로 검토해야 한다.

## 제공기관 문의 및 후속 단계

문자: SN 유일성·재발송/수정/철회 관계, crtDt 포함 경계·상한·timezone, 지연/수정 재조회, 안정 정렬·snapshot·totalCount/echo/빈 결과/최종 페이지, 지역 ID 체계를 확인해야 한다.

산불: 공식 사건 ID namespace·길이·불변성, 권위 있는 lifecycle 코드 및 취소/종료 규칙, 수정 sequence와 최신수정 필드, 과거/삭제 이력, startDt 경계 및 snapshot, 실제 영향지역의 근거, SN 연계표와 LINK 필드 의미, upstream 제공기관을 확인해야 한다. 담당자·신고 위치 등 개인정보의 최소수집·ACL·보존/삭제 승인이 선행되어야 한다.

두 API 모두 공식 사건 Evidence, ProjectionAdmission, 페이지 완전성, 증분 계약, quota 및 Archive 정책이 충족되기 전 운영 승인 불가다. 원문 관측과 사건 확정은 별개다. 기존 인프라의 테스트 통과로 이 외부 계약을 대체하지 않는다.

## 변경 파일

- disaster/collection/OfficialProviderClient.java: 메시지 전용 strict decode, 기존 decoder 재사용.
- disaster/collection/OfficialCollectionProviderPlugin.java: MOIS_MESSAGES만 strict profile 연결.
- disaster/collection/OfficialRecordNormalizer.java: 메시지 안정 ID 자료형 검증.
- disaster/collection/MoisMessagesContractTest.java (test, 신규): 18개 합성 protocol/비활성/호환 테스트.
- disaster/collection/MoisRawCollectionIntegrationTest.java (test): 8개 PostgreSQL raw/날짜/키워드 격리 테스트 추가.
- disaster/projection/OfficialCollectionProjectionPostgresTest.java (test): 12개 PostgreSQL 완전성/워터마크/계약 분리 테스트 추가.
- backend/docs/collection/PHASE4D3_MOIS_CONTRACT.md: 본 보고서.

## 테스트 기록

초기 재현: 신규 단위 18 발견/9 통과/9 실패/0 생략. parser 및 안정 ID 검증 결함을 확인했다. 초기 담당 회귀는 283 발견/280 통과/1 실패/2 생략으로, 신규 산불 source fixture를 기존 seed 조회 helper로 찾으려던 테스트 준비 오류였다. 테스트 내부에만 독립 합성 source/contract를 생성하도록 수정했고 운영 seed·Migration·기대값은 변경하지 않았다. DB contract flag가 true여도 플러그인은 FAILED/CONTRACT_REQUIRED, HTTP 0이어야 한다는 검증을 유지했다.

최종 담당 회귀: 283 발견/281 통과/0 실패/기존 생략 2. 신규 테스트는 단위 18, 실제 폐기 PostgreSQL 20개(원문 8+Projection 12)다. 이는 합성 계약 검증이며 실제 MOIS 응답 성공이 아니다.

전체 clean build 및 정리 결과는 아래 최종 검증 기록에 추가한다. 초기/담당 XML과 로그는 로컬 TEMP/eodiisseo-stage4d3-20261115에 구분 보존하며 커밋하지 않는다.
## 재현 환경과 실행 명령

승인된 disposable PostgreSQL 17을 사용했다. 기본 감사 DB는 127.0.0.1:55433이며 각 PostgreSQL 테스트가 고유 폐기 DB를 만들고 정리한다. 기존 4C 회귀에만 기존 하네스가 만드는 별도 TEMP data directory의 127.0.0.2:55433 및 127.0.0.1:55435 TLS 인스턴스를 사용한다. 원래 감사 서버에 TLS 확장을 설치하거나 재시작하지 않는다. 합성 회원·임시 LOGIN·인증서만 사용하며 실제 .env/키는 읽거나 수정하지 않는다.

프로세스 환경: EODIISSEO_LOCAL_DB_TESTS=true, EODIISSEO_AUDIT_DISPOSABLE=true, EODIISSEO_AUDIT_JDBC_URL=jdbc:postgresql://127.0.0.1:55433/postgres, SPRING_FLYWAY_ENABLED=true, AUTH_BRIDGES_ENABLED=true. 전체 회귀에서는 기존 TLS 하네스 경로와 EODIISSEO_GATEWAY_TLS_TESTS/EODIISSEO_TLS_TESTS=true를 명시한다. COLLECTION_WORKER_ENABLED/OFFICIAL_COLLECTION_ENABLED=false를 유지한다.

담당 회귀 선택자: *Mois*Test, *OfficialWildfireInfoInspectorTest, *OfficialCollectionProjectionPostgresTest, *CollectionProviderPluginTest, *CollectionCoordinatorPostgresTest, *OwnedRuntimeSeparationIntegrationTest, *RainCodesContractTest, *EarthquakeResponseContractTest.

전체 명령(위 테스트 전용 프로세스 환경/하네스 준비 후):

```powershell
.\backend\gradlew.bat -p backend clean build `
  "-PbuildOutputDir=$env:TEMP\eodiisseo-stage4d3-build" `
  --project-cache-dir "$env:TEMP\eodiisseo-stage4d3-cache" `
  --max-workers=1 --no-daemon --console=plain
```

Gradle은 순차 실행했다. Fake HTTP와 합성 JSON은 실제 API 수신으로 집계하지 않는다. 기존 rollback·lease·quota·Gateway·Fleet·Barrier 테스트도 함께 실행하되 새로운 운영 승인을 생성하지 않는다. 테스트 결과는 JUnit XML의 발견/실패/오류/생략을 합산하고 생략을 통과에 포함하지 않는다.
## 최종 전체 검증 및 보존 결과

- 전체 clean build: **1,860 발견 / 1,838 통과 / 0 실패 / 22 생략**, BUILD SUCCESSFUL, 17분 51초. 이전 기준 대비 신규 38개가 증가했고 모두 실행·통과했다.
- 신규 단위 18개와 PostgreSQL 20개는 전체 집계에 포함된다. Fake Transport/합성 자료이며 실제 제공처 계약 입증으로 승격하지 않는다.
- 기존 MOIS 문서 계약 6개, 페이지 metadata 8개, 원문 PostgreSQL 전체 37개, 산불 Inspector 36개, 공식 Projection PostgreSQL 전체 66개 통과. 기존 4C 명령/Fleet PostgreSQL 13개, Gateway-over-TLS 6개도 통과했다.
- 생략은 기존 외부 실호출 설정 14, Disabled 5, RAG scope 2, 실제 pgvector 1이다. 생략 조건·기대값·timeout을 변경하지 않았으며 생략을 통과로 집계하지 않았다.
- 기존 Collection 관련 11개를 포함한 SQL 총 23개와 비공개 catalog의 SHA-256이 시작 시점과 동일하다. 기존 Migration 수정·신규 Migration 없음. git diff --check 통과.
- 이번 실행에서 만든 TLS 서버 2개와 해당 TEMP data directory·인증서·임시 credential을 정리했다. 테스트용 Collection 임시 DB/역할 잔존 수는 각각 0이다. 이번 bootstrap 비밀번호의 로컬 로그 일치 수는 0이다.
- 작업 전부터 존재한 owned_* DB 1개는 이름·소유자·생성시각·연결 수가 기준과 동일하다. 해당 기존 데이터는 삭제/수정하지 않았다. 원래 audit PostgreSQL은 재시작·설정변경하지 않았다.
- 원문/Fixture/공개 문서 HTML·테스트 XML·ZIP·개인 catalog는 커밋 대상이 아니다. 기존 비공개 catalog는 untracked 상태와 해시를 유지한다. 실제 인증정보·.env 접근 없음.
- 코드·테스트 커밋: `e8a1fb333d6d7b6b6ac8b8a72ccaf72b4f812985` (6개 파일).
- 운영 판정: **두 계약 모두 운영 수집 불가/차단 유지**. 메시지는 증분·수정 replay·사건 연결, 산불은 사건 ID 범위·상태·수정 순서·영향지역·페이지/증분·이용승인 근거가 부족하다. PreparedCollectionRuntime/7개 API 승인 차단과 기존 스케줄러 상태는 바꾸지 않았다.
- Push·PR·배포·실제 Worker 교체·실제 데이터 API/AI/FCM 호출 없음. 4D-3 종료 후 운영 전환이나 다음 단계를 자동 실행하지 않는다.