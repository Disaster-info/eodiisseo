# 7차 호우 수집 계약 검증 보고서

검증일: 2026-11-04. 경로: C:\Users\dkemf\source\eodiisseo-integration-ops-latest
브랜치: feature/ai-dashboard-noti. 시작/종료 기준 HEAD: 33824c5b53934ae897677e2da93c3bc4aa22fd94.
작업 시작 시 clean. Commit/Stage/Push/브랜치·Worktree 생성 없음.

## 결론

공식 문서는 totalCount를 전체 데이터 수로 설명하지만 실제 두 페이지가 각각 5행/totalCount=0이다. 모순 원인은 확정하지 못했다. PAGE_TOTAL_INCONSISTENT를 유지하며 완전 수집·Cursor/Coverage 진전·사건 확정을 허용하지 않았다. 행 수로 count를 대체하거나 짧은 페이지로 완료를 추정하지 않았다.

## 공식 문서와 확인 범위

- [공공데이터포털 기상특보 조회서비스](https://www.data.go.kr/data/15000415/openapi.do)
- [최신 첨부 다운로드](https://www.data.go.kr/cmm/cmm/fileDownload.do?atchFileId=FILE_000000003648415&fileSn=1)
- 기상청21_기상특보 조회서비스_오픈API활용가이드_260601.zip, 433648 bytes
- SHA256: 6CB5F8E52DF4709D5E7DAFF129948A33620BF708B9F9ABD405480E07B0B32CA4
- ZIP 내 DOCX의 getPwnCd 요청/응답 표를 검토했다. totalCount는 데이터 총 개수, numOfRows는 한 페이지 결과 수, pageNo는 페이지 번호이다.
- tmSeq는 발표번호(월별)이다. 전역 사건 ID로 사용할 근거가 아니다. areaCode는 특보구역 코드이며 행정구역 전체 대응을 증명하지 않는다.
- 요청 warningType과 응답 warnVar를 구분한다. 호우 값은 2, 지점은 108.
- command: 1발표/2해제/3연장/6정정/7변경/8변경해제. cancel: 0정상/1취소.
- startTime/endTime/allEndTime은 조건부 필드이다. 0을 유효한 날짜로 바꾸지 않는다.
- 이 문서에서 count=0의 별도 의미, 빈/짧은 페이지의 안전한 종료, 페이지 정렬·스냅샷 보장, 날짜 경계 및 60일 규칙의 충분한 완전성 계약을 확보하지 못했다. 설명 부재를 특정 제공처 동작의 증명으로 사용하지 않았다.

## 제한적 실호출

승인된 개발 키를 비밀설정에서 읽었으며 키/인증 URL/원문 전체를 출력하지 않았다. 공식 문서 검토 후 2026-09-26 하루에 한정해 pageNo=1,2 / numOfRows=5 / warningType=2 / stnId=108로 정확히 2 GET을 수행했다. 이 날짜는 검증일 기준 39일 전이다. 자동 수집/날짜 탐색/추가 호출 없음.

| 항목 | 페이지1 | 페이지2 |
|---|---:|---:|
| HTTP/resultCode | 200/00 | 200/00 |
| resultMsg | NORMAL_SERVICE | NORMAL_SERVICE |
| 수신 행/totalCount | 5/0 | 5/0 |
| command | 2:5 | 2:3, 7:2 |
| warnStress | 0:4, 1:1 | 0:2, 1:3 |
| cancel | 0:5 | 0:5 |
| startTime | 0:5 | 12자리:2, 0:3 |
| endTime | 12자리:5 | 12자리:3, 0:2 |
| allEndTime | 12자리:4, 0:1 | 0:5 |

두 페이지 간 동일 발표 복합 식별 중복은 0이었다. 복합 키는 발표·구역 식별이며 사건 ID가 아니다. 기존 기준 발표 #09-111의 호환성 후보는 이 10행에서 0건이다. 실제 변경·해제 행을 관측했지만 동일 특보의 전후 관계, 발효·취소·정정·역순 도착은 이 표본으로 증명하지 못했다.

## 저장·안전성·코드

폐기용 PostgreSQL에 원문 2페이지를 실제 저장하여 byte 동일성과 page_no=1/2를 확인하고 롤백을 검증했다. 생성한 개발 DB는 검증 후 폐기했다. 실제 운영 DB/서버/.env는 변경하지 않았다.

OfficialDevelopmentLiveValidationTest에 유한 1,2 페이지 관찰 옵션과 실제 요청 페이지의 원문 보존, 익명 집계/중복 발표 관찰을 추가했다. 생산 수집 종료 규칙은 변경하지 않았다.
원문 수신 성공은 완전 수집 성공이 아니다. 실제 사건/Revision/Outbox/요약/성공 Cursor/Coverage를 만들지 않았다. 영향지역 UNKNOWN과 사건 미확정 정책을 유지한다.

기존 합성 테스트로 페이지 모순·부분 실패·재조회·Cursor 보존·사건 확정 제한을 회귀한다. 합성 테스트 성공을 실제 제공처 완전성 계약으로 해석하지 않는다.

## 제공기관 문의

1. 정상 응답에서 items>0인데 totalCount=0인 원인과 공식 처리 계약.
2. 신뢰할 마지막 페이지/빈 페이지 규칙, 안정 정렬 키, 조회 중 페이지 변경 및 스냅샷 지원.
3. 조회 범위 60일 및 시작/종료 날짜 포함 경계, 지연 등록·수정 데이터의 재조회 정책.
4. 월별 tmSeq/여러 특보구역 행의 식별 범위와 발표 간 연결/정정 참조.
5. 특보구역-행정구역 전체 대응 자료와 시점별 유효기간.

이 계약 전에는 운영 완전 수집을 활성화할 수 없다. 호우특보를 피해 발생 사건으로 자동 확정하지 않는다.

## 테스트 및 변경 범위

실호출 전용 실행: 16건 중 5통과/0실패/11생략. 지진·문자 등 이번 호우 실행 대상 밖의 조건부 케이스가 생략되었다. 호출 2회와 원문 저장은 통과했으며 완전성은 보류이다.
전체 회귀 결과와 공통 변경 목록은 아래 최종 검증 절에 기록한다.

## 최종 검증 (2026-11-04)

- 전체 명령: backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain -PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase7-final-20261104
- BUILD SUCCESSFUL, 2분36초. JUnit XML 합계 1040건: 1022통과 / 실패0 / 오류0 / 생략18.
- 생략: 실제 ChatRag/Ollama4, 실제 재난 API6, 실제 RAG import opt-in1, 실제 FCM OAuth1, 실제 날씨1, FCM 실자격증명 wiring1(설정 미제공), Docker 필요 auth DB4(Docker 가용 조건 미충족). 생략은 통과로 집계하지 않았다.
- 별도 실제 호우 실행: 5통과/0실패/11생략, GET2회.
- 별도 실제 RAG 최종 실행: 1통과/0실패/0생략, 외부 API/AI 호출 없음. XML 안전 집계: rain canonical_preview=1/example_previews=[1,2], wildfire canonical_preview=1/example_previews=[0,0], 양쪽 approved=0/runtime_results=0/rollback=OK.
- 기존 ChatRagIntegrationTest 22건 및 ResourceServiceTest 44건 통과. 전체 회귀에 승인 철회/만료/원문 변경/인용/AI 대역 및 수집 안전 정책 검증이 포함된다. 실제 미승인 자료로 AI 응답을 생성하지 않았다.
- 전체 회귀는 외부 실호출 플래그를 끄고 폐기용 테스트 DB를 사용했다. 운영 DB/실제 .env/키/실행 서버 변경 없음.
- git diff --check 통과. HEAD/브랜치 유지. staged 파일0.
- 변경 총12파일: 기존9 + 신규3. 수정 허용 영역과 개발 보고서에 한정. auth/외부 collection/area/location/group/common/integration/build/Migration/frontend 변경0.

### 전체 변경 파일

1. backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/ResourceService.java
2. backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/GuidanceSourcePlan.java
3. backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/KoreanLexicalParagraphSearch.java
4. backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/adapter/OfficialGuidanceImporter.java
5. backend/src/main/java/com/disasterinfo/eodiisseo/ai/resource/PHASE7_SOURCE_CATALOG.json (신규, 메타데이터만)
6. backend/src/test/java/com/disasterinfo/eodiisseo/ai/resource/GuidanceSourcePlanTest.java
7. backend/src/test/java/com/disasterinfo/eodiisseo/ai/resource/OfficialGuidanceImporterTest.java
8. backend/src/test/java/com/disasterinfo/eodiisseo/ai/resource/ResourceServiceTest.java
9. backend/src/test/java/com/disasterinfo/eodiisseo/disaster/OwnedRuntimeSeparationIntegrationTest.java
10. backend/src/test/java/com/disasterinfo/eodiisseo/disaster/collection/OfficialDevelopmentLiveValidationTest.java
11. PHASE7_RAIN_CONTRACT_REPORT.md (신규)
12. PHASE7_RAG_REAL_DATA_REPORT.md (신규)

실제 원문 검토 산출물과 테스트 빌드 결과는 Temp에만 있으며 Git 변경 파일에 포함되지 않는다. 모든 변경은 미커밋·unstaged 상태로 남겼다.

## 최종 추가 회귀 (2026-11-06)

승인 후 검색 검증 테스트 추가 뒤 전체 clean build를 다시 수행했다.
명령: backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain -PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase7-final-20261106
BUILD SUCCESSFUL(1분57초). XML 총1041건: 1022통과/실패0/오류0/생략19.
생략은 기존18건에 이번 실제문서 개발 승인 opt-in1건을 더한 것이다. 일반 회귀에서 실문서 자동 승인을 막기 위해 해당 플래그를 해제했고, 이 승인 테스트는 별도 실행에서 1통과/실패0/생략0을 확인했다. 이전 회귀와 중복 합산하지 않는다.
그 밖의 생략 사유는 실 ChatRag/Ollama4, 실 재난 API6, 이전 실 RAG import opt-in1, 실 FCM OAuth1, 실 날씨1, FCM 자격증명 wiring1, Docker 필요 auth DB4이다.
이번 승인 응답 후 변경한 파일은 OwnedRuntimeSeparationIntegrationTest.java 및 두 보고서뿐이다. 기존 7차 변경은 보존했다. 전체 변경 파일은 기존 목록과 동일한12개이며 추가 production 변경 없음.
HEAD 33824c5b53934ae897677e2da93c3bc4aa22fd94 / feature/ai-dashboard-noti 유지. staged0. Commit/Push/브랜치·Worktree 생성 없음. 타 담당자 파일/공용 설정/Migration/.env/운영 서버 변경 없음.
호우 계약 불확실성은 해결된 것으로 간주하지 않으며 PAGE_TOTAL_INCONSISTENT를 유지한다. 실제 문서 개발 승인·검색·철회 결과는 RAG 보고서의 2026-11-06 절을 참조한다.
