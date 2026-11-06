# 7차 공식 RAG 실데이터 구축 보고서

자료 검수일: 2026-11-04. 사용자 승인 후 추가 검증일: 2026-11-06. 브랜치 feature/ai-dashboard-noti.
HEAD 33824c5b53934ae897677e2da93c3bc4aa22fd94. 변경은 전부 unstaged.

## 결론과 승인 상태

공식 호우 1문서/6문단, 산불 1문서/4문단을 11월4일 폐기용 개발 DB에 IMPORT/INSPECT하고 미승인 검색 차단을 확인했다. 11월6일 사용자가 동일 추출본의 개발 DB 검색용 승인을 명시하여 재IMPORT/INSPECT→APPROVE→SEARCH→REVOKE를 완료했다. 개발용 승인 2건/검색 성공 2문서/철회 2건이며 종료 시 활성 승인 0건이다. 개발 DB는 폐기했다. 운영 승인·영구 적재·외부 AI 전송은 수행하지 않았다.

## 공식 자료·이용조건

[공공누리 정책](https://www.kogl.or.kr/static/html/opencode.html), [행정안전부 저작권정책](https://www.mois.go.kr/frt/sub/a08/copyrightPolicy/screen.do)을 검토했다. 제1유형은 출처 표시 조건의 이용·변경을 허용하지만 자료 내 제3자 권리까지 일괄 보장하지 않는다. 아래 텍스트 범위만 다루고 이미지·로고·QR·앱 화면은 복제하지 않았다.

| 재난 | 공식 게시물/날짜 | 권리 및 원본 표기 | 처리 |
|---|---|---|---|
| 지진 | [행안부 보도자료](https://mois.go.kr/frt/bbs/type010/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000008&nttId=89696), 2022-01-02 | 게시물 제1유형. 다른 연도 책자에 권리 전이 금지 | PDF 다운로드·텍스트 추출, 적재 제외 |
| 호우 | [논산시 행동요령](https://nonsan.go.kr/kor/html/sub05/050610.html?mode=V&no=b3a79c4a1274bdfe06e9cd3418efd51f), 2026-06-09 | 게시물 제1유형 명시. 논산시 게시, 첨부 행안부 표기 | 첨부 file_id=222656의 안전 문구 6블록 |
| 산불 | [농촌진흥청 행동요령](https://www.rda.go.kr/board/board.do?dataNo=100000801828&mode=view&prgId=con_cardnews), 2025-03-28 | 게시물 제1유형 명시. 농진청 게시, 그림 산림청 표기 | 본문 IMG ALT 4단계, 그림과 대조 |

게시기관을 원저작권자로 단정하지 않았다. 제목에 원본 기관 표기와 게시기관을 함께 보존했다. 서비스 재사용 범위는 확인한 안전 텍스트에 한정한다. 향후 전체 이미지/다른 첨부/새 버전은 별도 권리 검토가 필요하다.

## 추출·문단 검수

지진 PDF는 HTTP200/357612bytes/3페이지이며 텍스트 계층을 추출하고 모든 페이지를 렌더링해 확인했다. 2022 정책·코로나 지침과 작은 도해 중심이고 도해의 일반 행동 본문은 텍스트 계층에 없었다. 오래된 정책/코로나 문구를 현재 일반 행동요령으로 적재하지 않았다. OCR을 강행하지 않았으며 METADATA_ONLY 유지.

호우 이미지는 ALT가 일반 이미지 설명이므로 ALT를 행동 본문으로 사용하지 않았다. 이미지 육안 대조로 장소별 제목·조건·부정어·사전 준비와 발생 중 행동을 포함한 완전한 6블록을 전사했다.
산불은 제공기관의 상세 ALT에서 실제 4단계 행동 문단을 추출하고 이미지와 대조했다. 중간 앱 화면/QR 설명은 제외했다.
두 자료 모두 본문 중간을 요약하지 않고 HTML p 경계로 보존했다.

실제 원문/이미지/PDF/추출 본문은 Git에 포함하지 않았다. Temp의 manifest.json, *-export.html, PHASE7_RAG_REVIEW_PACKET.md에서 문단·출처·hash를 검토할 수 있다:
C:\Users\dkemf\AppData\Local\Temp\eodiisseo-phase7-docs-20261104
검토본에는 개발 DB의 INSPECT ID/Revision fingerprint도 기록했다. DB 폐기 후 실제 운영 승인에는 재IMPORT/INSPECT와 해당 새 fingerprint 검토가 필요하다.

## 기존 코드 활용과 변경

OfficialGuidanceImporter/GuidanceAdminTool/GuidanceSourcePlan/ResourceService의 PLAN→IMPORT→INSPECT 흐름을 재사용했다.
- 논산시·농진청의 정확한 host와 publisher를 결합했다. 정부 도메인 wildcard나 자동 권리 부여가 아니다. 기존 행안부 자료 호환을 유지한다.
- ResourceService의 행안부 고정 조건을 정확한 publisher/host 검증으로 확장했다. 승인·시한·fingerprint 검사는 유지한다.
- GuidanceSourcePlan에 scoped rightsEvidence, 형식 검증된 contentFingerprint, PLACE 메타데이터를 허용하되 결과는 PENDING/runtimeSearchEligible=false이다.
- PHASE7_SOURCE_CATALOG.json에는 본문 없이 출처·기관·날짜·위치·권리 범위·hash만 저장한다.
- 정적 호우 질문의 명시적 조건문/지하 공간 표현을 검색하고 실시간·현재 위험도/경로 판단 차단을 유지한다.

sourceVersion은 게시일+확인일의 내부 검토 버전이다. 제공기관이 부여한 공식 버전이라고 주장하지 않는다.
원첨부 SHA256, 텍스트 export SHA256, 정규화 본문 hash, document Revision fingerprint는 서로 다른 목적이다. 승인 검증에는 기존 전체 document fingerprint를 사용한다.
section/sourceLocator/rightsEvidence 등 추가 메타데이터는 SourcePlan/catalog/개발 검토 산출물에 있다. 기존 DB에 문단별 새 컬럼이 있다고 가정하지 않았다. 새 Migration/공용 스키마 변경 없음. 향후 HTTP 인용에 세부 문단 위치를 구조적으로 노출하려면 별도 모델 계약 검토가 필요하다.

## 실제 등록·검색 적합성

독립 개발 DB에서 실제 export hash/문단 전체 동일성/기관명/원문 bytes 저장을 검증했다. 변경 export 등록의 트랜잭션을 롤백하여 기존 fingerprint와 건수가 보존됨을 확인했다.
PLAN 2/IMPORT 2/INSPECT 2, 승인 0/미승인 문단 10. review 0/outbox 0.
11월4일 ResourceService 승인 corpus 검색 0은 의도한 미승인 차단 결과이다. 11월6일 승인 후 서비스 검색 결과는 아래 추가 검증 절에 기록했다.

아래는 승인 게이트를 통과한 서비스 응답이 아닌, 추출 문단에 대한 오프라인 검색 적합성 검사이다.

| 지시서 질문 | 검수용 일치 문단 | 판정 |
|---|---:|---|
| 지진, 건물이 흔들리는 동안 | 미실행 | 적합한 지진 corpus 없음 |
| 지진, 엘리베이터 | 미실행 | 적합한 지진 corpus 없음 |
| 호우, 지하차도 이동 허용 | 1 | 원문 금지 문단 존재 |
| 집중호우 발생 시 지하 공간 | 2 | 장소/대피 문단 존재 |
| 산불 대피 안내 시 준비물 | 0 | 현재 추출 자료로 준비물 근거 부족 |
| 산불이 가까워졌을 때 방향 | 0 | 정적 자료로 현재 대피 방향 추정 금지 |

호우/산불의 일반 행동 canonical query는 각각 1문단을 찾았다. 문단 검색 개선을 AI 생성 품질 개선으로 해석하지 않는다.
실제 Ollama/FCM/GPU 호출 없음. 실제 corpus의 검색 결과에서 문단·출처 메타데이터는 검증했으며 모델 답변·인용 생성 성공은 미검증이다. 기존 합성 ChatRag/Ollama HTTP 대역에서 인용·철회·만료·변경·프롬프트 인젝션·AI 실패의 회귀를 수행한다.

## 남은 작업

1. 동일 추출본의 개발 검색용 명시적 승인과 서비스 검색은 2026-11-06 완료했다. 운영용 승인과 영구 적재는 별도 검토가 필요하다.
2. 지진의 최신 일반 행동 본문 및 첨부별 권리가 확인된 자료 확보.
3. 산불 준비물 등 현재 자료의 근거 부족을 보충할 공식 자료 검토. 실시간 방향은 정적 RAG로 해결하지 않는다.
4. 승인 corpus의 서비스 검색·원문 및 출처 일치는 완료했다. 이 실제 corpus의 Ollama 대역 답변·최종 인용 생성과 실모델 평가는 별도 후속 작업이다.
5. 운영 배포/영구 적재/자료 보존·만료 재검토는 별도 승인 작업.

## 테스트 및 변경 범위

실제 RAG IMPORT/INSPECT 전용 테스트 최종 1통과/0실패/0생략. 단위 ResourceService+ChatRag 대상 66통과/0실패/0생략.
전체 회귀 및 변경 파일 목록은 아래 최종 검증 절에 기록한다.

## 최초 전체 회귀 기록 (2026-11-04)

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

## 2026-11-06 사용자 승인 후 실제 corpus 서비스 검색

사용자 응답: "지시 범위외에는 건드리지 않는 선에서 승인". 승인 대상은 검토본의 호우6/산불4문단과 동일한 export만이며 폐기용 개발 DB 검색 테스트에 한정한다. 운영 승인/지진/실제 모델/FCM/추가 수집은 제외했다.

새 테스트 humanReviewedRealExportsApproveSearchAndRevokeOnlyInDisposableDatabase는 별도 opt-in으로만 실행된다. 일반 회귀에서 자동 승인하지 않는다. 실제 본문은 계속 Git 밖 Temp에 있다.
두 문서의 export SHA256/파일명/URL/게시기관/제목을 고정값과 비교하고, 둘 다 동일한지 확인한 뒤에만 IMPORT한다. 바뀐 자료에 이 승인을 자동 재사용하지 않는다.

| 자료 | 승인된 export SHA256 |
|---|---|
| 호우 | 4f207f9218c419ec844b50261d53abffe2aa8eb39b20efb038537ad817a6c17b |
| 산불 | 96d2f4fe1f39a006cc36f66ec30417ca8215649dd4d81e0ac15c8f0d9d4f99fa |

기존 GuidanceAdminTool의 IMPORT→INSPECT→APPROVE→ResourceService SEARCH→REVOKE를 사용했다. 새 개발 문서의 정확한 Revision fingerprint로 최대10분 승인하고 검증 직후 철회했다. 실제 ACTIVE 세션/OPS 권한 검증은 그대로 사용했다. 테스트 DB의 public evidence 승인 필드는 이 격리 검증에서만 사용되었으며 외부 AI 전송 동의나 운영 승인이 아니다.

| 검사 | 결과 |
|---|---|
| 승인 전 서비스 검색 | 해당 문서 제외 |
| OPS 권한 없는 APPROVE | REJECTED |
| 다른 Revision으로 APPROVE | REJECTED |
| 정확한 Revision APPROVE | 두 문서 성공 |
| 호우 canonical 질문 | 원문1문단 |
| 호우 지하차도 이동 질문 | 원문1문단 |
| 집중호우 지하 공간 질문 | 원문2문단 |
| 산불 canonical 질문 | 원문1문단 |
| 산불 준비물·실시간 방향 질문 | 근거 부족/검색0 |
| 지진 두 질문 | corpus 없음/검색0 |
| 검색 출처 URL/게시기관/제목 | 원자료 메타데이터와 일치 |
| 검색 본문 | 저장된 완전한 원문 문단과 일치 |
| REVOKE 후 검색 | 두 문서 제외 |
| 재난 Outbox | 증가 없음 |

승인 검색 전용 실행: 1통과/실패0/오류0/생략0, BUILD SUCCESSFUL(37초).
승인 처리2/철회2/종료 시 활성 승인0. 폐기용 DB 생성·삭제는 기존 안전한 테스트 lifecycle을 재사용했다.
실제 Ollama/FCM/공공 API 추가 호출0. 이번 추가 변경은 담당 통합 테스트와 보고서뿐이다. 호우 수집 차단 정책과 운영 corpus는 그대로다.
이 실제 corpus로 모델 응답 생성 및 최종 답변 인용을 성공했다고 주장하지 않는다.

## 최종 추가 회귀 (2026-11-06)

승인 후 검색 검증 테스트 추가 뒤 전체 clean build를 다시 수행했다.
명령: backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain -PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase7-final-20261106
BUILD SUCCESSFUL(1분57초). XML 총1041건: 1022통과/실패0/오류0/생략19.
생략은 기존18건에 이번 실제문서 개발 승인 opt-in1건을 더한 것이다. 일반 회귀에서 실문서 자동 승인을 막기 위해 해당 플래그를 해제했고, 이 승인 테스트는 별도 실행에서 1통과/실패0/생략0을 확인했다. 이전 회귀와 중복 합산하지 않는다.
그 밖의 생략 사유는 실 ChatRag/Ollama4, 실 재난 API6, 이전 실 RAG import opt-in1, 실 FCM OAuth1, 실 날씨1, FCM 자격증명 wiring1, Docker 필요 auth DB4이다.
이번 승인 응답 후 변경한 파일은 OwnedRuntimeSeparationIntegrationTest.java 및 두 보고서뿐이다. 기존 7차 변경은 보존했다. 전체 변경 파일은 기존 목록과 동일한12개이며 추가 production 변경 없음.
HEAD 33824c5b53934ae897677e2da93c3bc4aa22fd94 / feature/ai-dashboard-noti 유지. staged0. Commit/Push/브랜치·Worktree 생성 없음. 타 담당자 파일/공용 설정/Migration/.env/운영 서버 변경 없음.
호우 계약 불확실성은 해결된 것으로 간주하지 않으며 PAGE_TOTAL_INCONSISTENT를 유지한다. 실제 문서 개발 승인·검색·철회 결과는 RAG 보고서의 2026-11-06 절을 참조한다.
