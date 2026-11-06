# 5차 지진·호우 실데이터 수신 및 파이프라인 검증 보고서

확인일: 2026-11-04(KST).
작업 경로: C:/Users/dkemf/source/eodiisseo-integration-ops-latest.
브랜치: feature/ai-dashboard-noti.
HEAD: 6e9b70b617598b04e5154cb57abe726fef602894.

## 결론과 완료 범위

지진·호우의 정상 발표 행은 확보하지 못했다. 실데이터 파이프라인 완성을 성공으로 보고하지 않는다.
호우 과거 조회의 99는 실제 응답에서 60일 조회 제한이 원인임을 확인했다.
지진 API허브의 명시적 getEqkMsg 요청은 HTTP 403으로 거부됐다.
재난문자 원문의 실제 날짜 형식·필드 타입·표본 중복과 폐기 DB 저장/롤백은 확인했다.
공식 사건 식별 관계가 없으므로 실제 사건·Revision·Outbox·AI 작업은 생성하지 않았다.

기존 구현을 재사용하여 KMA의 문서상 NO_DATA를 별도로 구분하고 실검증을 연결/존재/저장/차단/확정 계약으로 나눴다.
공식 계약이 부족한 작업을 임의의 사건 ID·지역 매핑·시간대 추정으로 완성하지 않았다.

## 1. Git·권한·기존 작업 보존

시작 시 4차 미커밋 파일 4개가 존재했다:
OfficialProviderClient.java, OfficialProviderClientTest.java, OfficialDevelopmentLiveValidationTest.java,
PHASE4_VALIDATION_REPORT.md.
이를 Reset/Stash/삭제하지 않고 기반으로 확장했다. 4차 보고서는 수정하지 않았다.
기존 기본 100행 및 선택적 1~100행 제한과 해당 회귀 테스트를 유지했다.
기존 03 실패 테스트는 요청에 따라 NO_DATA 구분을 검증하도록 확장했으며, 사건·Coverage 생성 금지는 그대로 검증한다.

신규 브랜치·Worktree 생성, Commit·Stage·Push·Merge·PR은 수행하지 않았다.
운영 DB·서버·실제 .env·인증키를 변경하지 않았다.
auth/collection/area/location/group/common/타 담당 integration 및 기존 공용 Migration·빌드·프론트엔드는 수정하지 않았다.
신규 Migration도 추가하지 않았다. 이번 변경은 disaster 내부 코드와 대응 테스트·보고서뿐이다.

## 2. 4차 03 및 endpoint 계약 재점검

| 항목 | 확인 결과 |
|---|---|
| 기존 공공데이터포털 지진 endpoint | https://apis.data.go.kr/1360000/EqkInfoService/getEqkMsg |
| 기존 호우 endpoint | https://apis.data.go.kr/1360000/WthrWrnInfoService/getPwnCd |
| 포털 인증 | serviceKey, 공공데이터포털 키를 한 번 URL 인코딩 |
| 호우 요청 | warningType=2, stnId=108. warnVar는 응답 필드로 구분 |
| 날짜·페이지 | fromTmFc/toTmFc는 발표일 YYYYMMDD, pageNo=1, numOfRows=5 |
| 4차 호출 기간 | 2026-11-03 하루, HTTP 200·숫자 03 |
| 공식 에러표 | 03/3 NODATA_ERROR는 데이터 없음으로 명시 |
| 4차 실제 resultMsg | 당시 원문 DB를 폐기했으며 보고서에 남기지 않아 이번에 소급 확인하지 못함 |
| 5차 과거 조회 | 지진 2024-06-12, 호우 2020-08-08. 두 서비스 모두 HTTP 200·99 관측 |
| 지진 Hub 별도 계약 | https://apihub.kma.go.kr/api/typ02/openApi/EqkInfoService/getEqkMsg, authKey |

3차에 저장한 공식 이용가이드의 DOCX XML을 다시 읽었다.
지진 가이드는 최근 3일 자료 제공으로 안내한다. 이를 API허브의 전체 지진 자료 보유기간과 혼동하지 않는다.
[기상청 지진 API허브 문서](https://apihub.kma.go.kr/apiList.do?seqApi=7)는 별도 authKey 계약과 과거 사례를 제공한다.
공공데이터포털의 지진/호우 키와 Hub 키를 섞어 보내거나 실패 시 운영 코드에서 자동으로 다른 키를 시도하지 않는다.

[공공데이터포털 지진 설명·첨부 가이드](https://www.data.go.kr/data/15000420/openapi.do),
[기상특보 설명·첨부 가이드](https://www.data.go.kr/data/15000415/openapi.do)의 공식 에러표를 근거로
KMA 응답의 code=03 또는 3 AND resultMsg=NODATA_ERROR 조합만 NO_DATA로 구분했다.
숫자 03만으로 모든 응답을 성공 또는 정상 빈 결과로 처리하지 않는다.
MOIS의 같은 숫자, 알 수 없는 메시지, 인증 오류, 스키마 오류는 이 예외에 포함하지 않는다.

NO_DATA는 CollectionFailure의 독립 코드와 OfficialCollectionService.Outcome.status로 전달한다.
공통 collection_run의 기존 상태 계약을 변경하지 않아 데이터 확보 작업은 FAILED/error_code=NO_DATA로 남고,
실제 데이터 수신·날짜 완전성 성공으로 기록하지 않는다. 기존 보수적인 재시도 정책도 유지한다.
새 통합 테스트로 records=0, 새 Coverage 없음, completed_through 미진전, 추가 사건 없음까지 검증했다.
따라서 장애와 구별하는 신호는 제공하지만 새로운 DB 상태나 HTTP 상태를 도입하지 않았다.

4차 실제 03의 메시지를 재확인하지 못했으므로 “그날 실제 발표가 전혀 없었다”거나
“모든 승인·인증·필터 조건이 검증됐다”고 단정하지 않는다.
문서의 03 의미와 이번 99/403의 확인된 원인을 각각 구분한다.

## 3. 날짜 선정·실호출 이력·승인 범위

사용자가 기존 세 제공처 키를 개발용·활용승인 완료라고 확인한 범위에서 .env를 읽기 전용으로 사용했다.
사용한 변수 이름은 KMA_PUBLIC_SERVICE_KEY, KMA_HUB_AUTH_KEY, SAFETYDATA_DISASTER_MESSAGE_KEY다.
키 값·인증 쿼리 URL·실제 원문·개별 SN·문자 내용은 Git·보고서에 포함하지 않았다.
키 이름과 사용자 확인은 제공처의 현재 접근 허용 상태를 보증하지 않으며, 실제 Hub 403을 별도로 보고한다.

지진 발표일 2024-06-12는 [기상청 2024년 8월 소식지](https://www.weather.go.kr/download_01/kma_202408.pdf)의
6월 12일 08:32 발표 사례에서 선정했다. 발생시각만 보고 정하지 않았다.
호우 2020-08-08은 [기상청 정책연구 보고서](https://www.kma.go.kr/kma/servlet/NeoboardProcess?bid=policy_research&fno=1&k=ATC202205160840491_a12187ec-3399-472d-ab4e-f2b743c1498a.pdf&mode=download&num=38)의
8월 8일 22:30 호우경보 변경 통보문 사례에서 선정했다.
발표가 존재하는 사실과 현재 선택한 endpoint가 그 일자를 제공하는 것은 다르다.

| 제공처 | 최초 실검증 | 두 번째 | 마지막 추가 1회 | 합계 |
|---|---|---|---|---:|
| 지진 | 포털 2024-06-12, HTTP200/PROVIDER_CODE | 같은 기간 숫자 진단: 99 | 같은 기간 Hub getEqkMsg: HTTP403 | 3 |
| 호우 | 포털 2020-08-08, HTTP200/PROVIDER_CODE | 같은 기간 숫자 진단: 99 | 같은 기간 포털: 99와 조회 한도 메시지 확인 | 3 |
| 재난문자 | crtDt=20261102, 5행/total36 | crtDt=20261103, 5행/total21 | 같은 crtDt=20261103, 5행/total21 | 3 |

총 실제 데이터 GET 9회. 모든 호출은 페이지 1, numOfRows=5이며 자동 날짜 순회·다음 페이지·대량 수집은 없었다.
첫 지진/호우가 실패해 다음 요청을 중단한 후 숫자 진단을 두 번째 요청으로 사용했다.
마지막 검증은 EODIISSEO_DEV_PROBE_REQUESTS=1을 강제하여 제공처별 단 한 번씩만 실행했다.
허용 횟수를 모두 사용한 뒤 추가 호출하지 않았다.

Java 호출은 기존 OfficialHttp/OfficialProviderClient의 15초 요청·5초 연결 제한,
2MiB 응답 제한, 리다이렉트/프록시 비사용, 요청 간격과 유한 횟수 제한을 재사용했다.
단발 숫자 진단도 15초·리다이렉트 금지·1초 간격이며 반복 재시도는 하지 않았다.
기존 운영 소스의 일일 예산·Cursor를 변경하지 않았고 새 폐기용 소스당 요청은 2회 이하라 기존 일일 1000회 상한보다 작았다.
자동 Scheduler·알림 Worker·실제 AI·FCM은 기동하지 않았다.

## 4. 지진·호우의 실제 결과와 보류 사유

### 지진

포털의 과거 조회에서 정상 발표 행을 얻지 못했다. 두 번째 진단 숫자 코드는 99다.
지진 가이드의 최근 자료 제한은 확인했지만, 해당 99의 실제 메시지는 첫 실행에서 판독하지 못했으므로
호우에서 확인한 60일 제한을 지진에도 그대로 적용했다고 주장하지 않는다.
별도 문서화된 Hub getEqkMsg+authKey는 실제 HTTP403으로 거부됐다.
원인이 키의 데이터셋 권한·유효 상태·IP 허용·제공처 제한 중 무엇인지는 확인하지 못했다.
인증키 교체·다른 endpoint 무단 탐색·보안 우회는 하지 않았다.

tmFc/tmEqk/tmSeq/cnt/fcTp/cor/lat/lon/mt/loc의 실제 행·타입·NULL 호환성은 미검증이다.
공식 사건 ID·발표 간 참조 관계도 미확보이므로 EARTHQUAKE_UNLINKED 정책을 유지한다.
API허브의 넓은 보유기간 안내만으로 getEqkMsg의 실제 과거 접근 가능성을 보증하지 않는다.

### 호우

마지막 실제 JSON 헤더는 code=99, 메시지는 “최대 조회 기간은 오늘 기준으로 60일 전까지입니다.”였다.
공식 과거 발표 사례로 날짜를 선정했으나 선택한 endpoint의 실응답 기준 조회 범위를 벗어난 것이다.
이는 4차의 03과 다른 원인이다. warningType/stnId 이름을 임의로 바꿔 해결하지 않았다.

현재 제한 안에서 공식 발표가 확인된 날짜를 새로 선정하여 조회하는 작업은 남았다.
이번 호출 한도를 소진하여 날짜를 바꿔 다시 호출하지 않았다.
실제 특보구역·발표/발효/해제·단계·취소 필드의 행을 확보하지 못했으므로 실제 호우 정규화 성공도 미검증이다.
특보 발표와 실제 피해 발생을 동일한 사건으로 자동 등록하지 않았다.

[공식 특보 DB 스키마](https://apihub.kma.go.kr/static/html/attach/wrn_table.html)의 REG_ID/ADM_ID 정의는 4차 확인을 유지한다.
전체 대응 데이터·버전·유효기간·우리 서비스 행정코드 호환성은 여전히 미확보다.
OfficialWarningAreaMappingPort 운영 구현은 추가하지 않았으며 영향지역 UNKNOWN을 유지한다.

## 5. 재난문자 실제 날짜·중복·수정 분석

실제 3응답 총 15행(중복 포함)을 받았다. 고유 문자 15건이나 산불 사건 15건으로 집계하지 않는다.
표본에서 SN은 JSON NUMBER, 나머지 검사 대상 필드는 STRING이었다.
개별 번호·실제 날짜값·문자 내용·지역명은 기록하지 않았다.

| 필드 | 관측된 형태 | 한계 |
|---|---|---|
| CRT_DT | yyyy/MM/dd HH:mm:ss | timezone/offset 없음. KST로 자동 변환하지 않음 |
| REG_YMD | yyyy/MM/dd HH:mm:ss.fffffffff (소수 9자리) | 등록일의 실제 시간대·정렬 의미 미확정 |
| MDFCN_YMD | 위와 같은 소수 9자리 형식 | 변경분 재조회·단조 증가 보장 미확정 |
| SN | NUMBER | 발표 식별 값. 산불 사건 ID 아님 |
| RCPTN_RGN_ID/RCPTN_RGN_NM, DST_SE_ID/DST_SE_NM | STRING | 수신지역은 확정 영향지역이 아님 |

최초 두 응답의 이 필드들에서 NULL·빈 문자열은 관측하지 않았다. 이것이 모든 자료에 대한 비NULL 보장은 아니다.
기존 4차 ISO 날짜 파서가 판독하지 못한 CRT_DT를 슬래시 형식으로 분류하여 값을 노출하지 않고 날짜 관계를 확인했다.
crtDt=20261102 표본은 작성일 기준 같은 날 4행·이후 날 1행,
crtDt=20261103 표본은 같은 날 5행이었다.
이는 당일 작성 자료와 이후 일자의 자료가 표본에 나타난 관측이며,
정확한 시간대·필터 대상 컬럼·>= 경계의 전체 계약이 증명된 것은 아니다.

처음 두 표본 사이 동일 SN 1개를 발견했다. canonical 내용 변경은 0개였다.
실제 수정 사례는 확보하지 못했다. 마지막 1회는 별도 DB 실행이므로 이전 실행과의 개별 SN 교차 대조를 수행하지 않았다.
정렬·페이지 이동·늦은 등록·수정 이력 재조회는 무누락 보장으로 확인되지 않았다.
따라서 INCREMENTAL_CONTRACT_REQUIRED, Coverage/Cursor 자동 진전 금지를 유지한다.

기존 합성 테스트의 같은 SN/다른 내용 보존, 페이지 내 중복 또는 다른 수정본 발견 시 전체 롤백,
과거 내용으로 최신 Revision을 덮어쓰지 않는 검증을 재사용했다.
실제 수정 사례 성공과 합성 수정 처리 성공을 혼동하지 않는다.
시간대·공식 사건 연결 근거가 없으므로 관측한 날짜 형식을 추정으로 운영 사건 파서에 연결하지 않았다.

## 6. 원문 저장·롤백·실제 사건 파이프라인

독립적인 폐기용 PostgreSQL DB를 실행별 owned_<UUID>로 생성하고 기존 canonical/owned Migration만 적용했다.
Spring 애플리케이션·Scheduler·Worker를 기동하지 않고 JdbcTemplate/기존 도메인 객체만 사용했다.
각 실행 종료 시 생성한 DB를 삭제했다. 원문을 Git Fixture로 내보내지 않았다.

| 단계 | 실제 결과 |
|---|---|
| 원문 응답 저장 | 총 7페이지: 최초 4 + 마지막 3 |
| 정상 데이터 원문 | 재난문자 3페이지/15행(중복 포함) |
| 오류 응답 원문 | 지진/호우 4페이지. 정상 재난 발표 저장과 구분 |
| 별도 숫자 진단 | 2개 응답은 저장하지 않음 |
| 바이트 동일성 | 저장한 raw_body를 읽어 원래 byte[]와 동일성 검증 |
| 롤백 | 원문 저장 후 강제 rollback, 기존 저장 페이지 수 불변 검증 |
| 발표 식별 | 재난문자 SN 확인. 지진/호우 정상 행 없음 |
| 공식 사건 확정 | 0건, 공식 연결 계약 미확보 |
| 실제 정규화·Revision·Outbox | 0건 |
| 실제 영속 AI 작업·Ollama·요약 저장 | 0건/미실행 |
| 실제 인증 HTTP 조회 | 사건 데이터가 없어 미검증. 비연결 원문이 목록 DTO로 노출되지 않는 내부 조회만 검증 |

부분 관측은 기존 collection_run에 FAILED/DEVELOPMENT_PARTIAL_PROBE로 저장한다.
전체 페이지 수집 SUCCESS나 데이터 부재 증명이 아니므로 Coverage·Cursor를 만들지 않는다.
롤백 테스트에서 임시 삽입한 페이지는 위 저장 성공 7페이지 건수에 포함하지 않았다.
재난문자 실제 행은 기존 OfficialRecordNormalizer/VerifiedIncidentService에
공식 Link 없이 전달하여 UNCLASSIFIED_MESSAGE 또는 WILDFIRE_MESSAGE_UNLINKED인 상태로 멈추는지 검증했다.
정규화 결과의 sourceRecord는 없고 disaster/source_disaster_revision/disaster_runtime_outbox/summary_runtime_task는 0건이었다.
호우는 공식 사건/전체 지역 계약을 확보하지 못해 피해 사건 정규화 호출 전에 멈췄다.
공식 원문과 AI 생성물의 책임을 합치거나 AI 실패로 원문을 삭제하는 처리는 추가하지 않았다.

## 7. 테스트 결과

### 전체 회귀·빌드

실호출 플래그를 모두 false로 두고 폐기용 로컬 PostgreSQL을 사용했다.
backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain
-PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase5-final-20261104 로 시작했다.
첫 전체 실행에서 새 테스트의 중복 endpoint_key 등록 Fixture 오류가 발견됐다.
기존 테스트 소스를 재사용하고 실행 전후의 Coverage·사건 수를 비교하도록 담당 테스트만 수정했다.
그 후 같은 출력 디렉터리에서 build를 다시 실행하여 전체 test 작업이 재실행됐고 BUILD SUCCESSFUL(2분 25초)로 끝났다.
중간 합성 검증에서 새 진단의 Java 정규식 이스케이프 컴파일 오류도 수정·재검증했다.

최종 JUnit XML 합계: 총 1,022 / 통과 1,006 / 실패 0 / 오류 0 / 생략 16.
비활성화된 실검증 클래스의 parameterized 메서드 5개는 제공처별 사례 생성 전 생략되어 XML에 5개로 집계된다.
Gradle 콘솔의 1,017개 완료·생략 11개와 차이나는 5개가 이것이며, 어느 쪽도 생략을 통과로 포함하지 않았다.
최종 주요 suite:
- OwnedRuntimeSeparationIntegrationTest: 30건 모두 통과.
- OfficialProviderClientTest: 34건 모두 통과.
- KmaGuideContractTest: 6건 모두 통과.

생략 16개:
- Ollama 실호출 3, RAG 실환경 1: 이번 실제 모델·운영 자료 검증 범위 밖.
- 기존 AssignedDatabaseIntegrationTest 4: Docker 없는 환경의 Testcontainers 조건.
- FCM OAuth 1, 실제 자격증명 파일 기반 Spring wiring 1: 이번 FCM 실검증/자격증명 주입 범위 밖.
- 실제 날씨 API 1: 대상 외 live flag 비활성.
- 재난 실검증 메서드 5: 전체 회귀에서는 비활성, 아래 별도 실제 실행 결과로 보고.

기존 29개 담당 통합 사례에 NO_DATA DB 회귀 1개가 추가됐다.
합성 공식 API 응답→수집→정규화→Revision→Outbox→영속 요약 작업→Ollama HTTP 대역→요약 저장→인증된 HTTP 조회,
사건 미연결·중복·정정/역순·호우 발효/변경/해제·매핑 UNKNOWN·원문 보존/롤백·RAG 승인/변경 철회 검증을 유지한다.
이 결과는 실제 지진/호우 발표 행 확보 또는 공식 사건 계약 획득의 성공이 아니다.

### 실제 API 검증 (전체 회귀와 별도)

첫 실행: eodiisseo-phase5-live-20261104, 15건 = 통과 6 / 실패 4 / 생략 5.
최종 추가 1회 실행: eodiisseo-phase5-live-final-20261104, 15건 = 통과 6 / 실패 4 / 생략 5.
두 실행 모두 Gradle FAILED이며 성공으로 숨기지 않았다. 중간 숫자 진단 GET 2회는 JUnit 성공 건수로 세지 않았다.
두 실검증은 같은 원인에 대해 기준별로 반복 검사했으므로 합산해 독립적인 실제 재난 사례 수로 주장하지 않는다.

| 검사 | 지진 | 호우 | 재난문자 |
|---|---|---|---|
| A 연결·정상 응답 계약 | 실패: 최종 HTTP403 | 실패: 99/조회 범위 제한 | 통과 |
| B 실제 발표 행 존재 | 실패: 0행 | 실패: 0행 | 통과 |
| C 원문 저장·동일 바이트·롤백 | 통과(오류 응답) | 통과(오류 응답) | 통과(정상 원문) |
| D 미확정 자료의 사건 파이프라인 차단 | 생략: 실제 행 없음 | 생략: 실제 행 없음 | 통과 |
| E 공식 사건 확정 계약 | 생략: 계약 없음 | 생략: 전체 계약 없음 | 생략: 사건 식별 계약 없음 |

최종 실제 데이터 수신을 기준으로 지진·호우는 미완료다.
공식 사건·Revision·Outbox·AI 작업 실증도 미완료이며 합성 테스트만 통과했다.
검증 결과 파일은 Temp의 위 실행 디렉터리 test-results/test 아래 있으며 실제 원문/개별 SN은 포함하지 않는다.
git diff --check 통과, 타 담당자 파일 diff 없음, staged 변경 0개.

## 8. 수정 파일과 영향

이번 5차에서 수정/추가한 6개 파일:
1. backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClient.java
   - 기존 4차 행 상한 유지. 정확한 KMA NO_DATA 별도 구분.
   - 명시적 earthquakeHub 팩토리 추가. 운영 자동 fallback·Scheduler 경로 변경 없음.
2. backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialProviderClientTest.java
   - 4차 상한 테스트 유지, 빈 결과 분류 기대값 구체화.
3. backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/KmaGuideContractTest.java
   - 03/3 정확한 메시지, MOIS 예외 제외, 미확인 메시지 차단, Hub authKey 고정 endpoint 검증 4건 추가.
4. backend/src/test/java/com/disasterinfo/eodiisseo/disaster/OwnedRuntimeSeparationIntegrationTest.java
   - 실제 수집 Service의 NO_DATA가 Coverage/사건을 생성하지 않는 DB 회귀 1건 추가.
5. backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialDevelopmentLiveValidationTest.java
   - 기존 4차 테스트를 제공처별 A/B/C/D/E 총 15개 독립 검사로 확장.
   - 각 관측은 같은 유한 GET 결과를 재사용하며 후속 검사는 네트워크를 재호출하지 않음.
6. backend/src/main/java/com/disasterinfo/eodiisseo/disaster/collection/PHASE5_VALIDATION_REPORT.md (신규)

최종 미커밋 작업 트리는 위 6개 + 그대로 보존한 PHASE4_VALIDATION_REPORT.md로 총 7개 파일이다.
기존 기능·DTO·HTTP 보안·ACTIVE 세션·Outbox·FCM·RAG 승인·개인정보 동의 정책의 구현은 변경하지 않았다.
타 담당자 기존 파일과 기존 Migration·빌드 설정의 변경은 0개다.
모든 변경은 unstaged이며 HEAD와 브랜치를 유지했다.

## 9. 필요한 공식기관·팀원 협의

1. KMA: 현재 getEqkMsg의 실제 제공 기간(가이드 최근 3일/별도 Hub 보유기간의 차이),
   Hub의 해당 세부 API 승인·키 유효성·IP/접근 제한과 HTTP403 원인을 확인해야 한다.
2. 호우: 현재 60일 범위 안에서 발표일이 공식 확인된 단일 날짜를 확보하거나,
   승인 가능한 과거 getPwnCd 자료 제공 경로·별도 신청 계약을 받아야 한다.
   이번 3회 제한 안에서는 정상 호우 행 확보까지 완료하지 못했다.
3. 4차 03의 실제 메시지·빈 응답 계약은 새 호출 한도를 부여한 후 독립적으로 확인할 필요가 있다.
   기존 raw 응답이 폐기되어 소급 판독은 불가능하다.
4. 사건 ID: 지진 cnt의 참조 대상, 발표 식별 범위·연도/월 초기화, 정정/취소·역순 갱신 계약을 받아
   VerifiedIncidentLinkPort의 정확한 근거·내용 해시·공식 순서로 연결해야 한다.
5. 지역 담당/KMA: 전체 REG_ID→ADM_ID 집합·누락/부분 구역·유효기간·분할/통합 이력·행정코드 체계를 확보해야 한다.
   이름 유사도·Repository 직접 조회로 우회하지 않는다.
6. MOIS: 슬래시 날짜와 소수 9자리 값의 공식 시간대, 필터 대상과 포함 경계,
   정렬/snapshot·수정·늦은 등록 재조회 규칙을 확인해야 한다.
7. 정상 발표 행을 확보한 뒤 공식 사건 연결이 확인되는 지점까지만 실제 정규화→Revision→Outbox를 검증한다.
   미확정 원문은 별도 보존하며 실제 Ollama 품질·FCM 발송·RAG 운영자료 승인 검증은 후속 범위로 둔다.
