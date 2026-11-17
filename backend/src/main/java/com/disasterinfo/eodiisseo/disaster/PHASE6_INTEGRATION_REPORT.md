# 6차 실데이터 연동·공식 RAG 구축·AI 운영 준비 보고서

확인일: 2026-11-04(KST).
경로: C:/Users/dkemf/source/eodiisseo-integration-ops-latest.
시작 HEAD: a87c4bd (test(disaster): 공식 API 실응답 검증 및 수집 오류 처리 개선).
브랜치: feature/ai-dashboard-noti. 시작 작업 트리는 깨끗했고 4·5차 내용은 이 커밋에 포함돼 있었다.

## 1. 결과 구분

| 작업 | 구현 완료 | 합성 테스트 | 실제 공식 데이터 | 실제 운영 환경 |
|---|---|---|---|---|
| 호우 정상 발표 행 | 기존 클라이언트 재사용·시각 호환 보완 | 통과 | 2회 정상 응답, 각 5행 | 미검증 |
| 원문 저장·롤백 | 기존 관측 DB 경로 재사용 | 통과 | 2페이지 저장·동일 바이트·롤백 검증 후 폐기 | 운영 DB 접근 안 함 |
| 완전 수집·Coverage | 총건수 모순 차단 코드 추가 | 통과 | totalCount=0/5행 모순으로 보류 | 비활성 |
| 실제 사건·Revision·Outbox | 기존 구현 유지 | 기존 경로 통과 | 사건 근거·전체 매핑 미확보로 생성 0 | 미검증 |
| 지진 Hub | 기존 403 분류·연결 코드 유지 | 관련 회귀 통과 | 이번 추가 호출 0 | 개별 접근 권한 미확인 |
| 공식 RAG 자료 | 출처 카탈로그·OPS PLAN·ALT 추출 보완 | 통과 | 출처/날짜/구조 조사, 본문 적재·승인은 보류 | 실제 검색 활성화 안 함 |
| 근거·승인·철회·환각 방지 | 기존 ResourceService/Importer/생성기 재사용 | 통과 | 실제 자료 승인·검색 성공 아님 | 미검증 |
| Ollama·GPU | 코드 점검·관리자 확인 자료 작성 | HTTP 대역 통과 | 실제 모델 호출 0 | SSH/설치/추론/VRAM 검증 없음 |

정상 HTTP 응답, 실제 발표 행, 원문 저장, 사건 확정, AI 생성, 운영 배포는 독립적인 결과다.
이번에 실제로 새로 확보한 것은 호우특보 해제 발표 표본이다. 피해 사건이나 영향지역 확정은 아니다.
RAG는 이용조건·사람의 승인이 해결되지 않아 실제 검색 corpus 구축을 완료하지 못했다.

## 2. Git·소유권·보안

기존 HEAD와 브랜치를 유지했다. 신규 브랜치/Worktree, Commit/Stage/Push/Merge/PR/Reset/Stash는 수행하지 않았다.
모든 변경을 unstaged 상태로 남긴다.
auth, 타 담당 collection/area/location/group/common/integration, 공용 Controller/Service/Repository,
기존 Migration·빌드·프론트엔드 변경은 0개다. 새 Migration도 없다.
이번 수정은 본인 disaster/ai 내부 코드와 대응 테스트·문서뿐이다.

.env는 승인된 개발키를 읽을 때만 읽기 전용으로 접근했다. 키 값·인증 URL·실제 재난문자 원문/SN은 기록하지 않았다.
운영 DB·실행 서버·인증키 변경, 실제 FCM/외부 AI 호출, Scheduler·알림 Worker 활성화는 하지 않았다.
GPU 접속·설치·모델 다운로드/로딩·추론·자원 점유·서비스 변경도 하지 않았다.
다른 프로젝트 “오늘 뭐 했어?” 코드는 읽거나 변경하지 않았다.

## 3. 호우 실데이터 검증

[공식 통보문 제09-111호](https://www.weather.go.kr/w/special-report/list.do?date=2026-09-26&kind=met&prevKind=met&prevStn=108&reportId=met%3A202609260340%3A111&stn=108)를 조회하여
2026-09-26 03:40 호우주의보 발표가 존재함을 확인했다.
호출일 2026-11-04 기준 39일 전이므로 5차에서 확인한 60일 제한 안이다.

기존 OfficialProviderClient를 그대로 사용했다.
endpoint는 https://apis.data.go.kr/1360000/WthrWrnInfoService/getPwnCd,
serviceKey=승인된 개발키, warningType=2, stnId=108, fromTmFc=toTmFc=20260926,
pageNo=1, numOfRows=5였다. 키가 포함된 URL을 로그/보고서에 남기지 않았다.
warningType은 요청, warnVar는 응답의 특보 종류이며 발표일로 조회한다.
기존 타임아웃·응답 크기·요청 상한을 유지했다.

최초 1회와 시각 호환성 수정 뒤 마지막 1회, 총 2회 GET만 수행했다.
자동 기간 순회·다음 페이지·필터 변경·다른 endpoint 재시도는 없었다.
두 요청 모두 HTTP200, resultCode=00, resultMsg=NORMAL_SERVICE, 5행을 반환했다.
두 표본 10행은 중복 포함 관측 건수이며 고유 발표 10건이라고 주장하지 않는다.
첫 페이지의 제09-111호 일치 건수는 0이다. 해당 일자의 정상 발표 수신과 지정 호수의 직접 수신을 구분한다.

### 실제 필드 호환성

반환 필드는 stnId, tmFc, tmSeq, areaCode, areaName, warnVar, warnStress, command, startTime, endTime, allEndTime, cancel이다.
tmFc/tmSeq/warnVar/warnStress/시각들은 NUMBER, stnId/areaCode/areaName/cancel은 STRING으로 관측됐다.
모든 표본은 warnVar=2, command=2(해제), cancel=0이었다.
warnStress는 주의보/경보 값이 관측됐고 중대경보 실제 행은 확보하지 않았다.
반환 발표일이 요청일과 일치하고 identity·필수 발표시각·조건부 시각이 기존 파서와 호환되는지 검증했다.
공식 단계와 공통 재난 severity를 임의 대응시키지 않았다.

startTime에는 숫자 0이 관측됐으며 allEndTime에도 0이 일부 있었다.
조건부 제공 시각의 0을 epoch나 발생시각으로 만들지 않고 “없음/미확정”으로 처리하는 warningTime()을 추가했다.
필수 time()의 엄격한 검증은 유지했다.
현재 normalizer의 start/end 조건부 처리에만 사용하고, 존재하지 않는 선행 발표·사건 시작일을 추정하지 않는다.
잘못된 비영(非0) 시각은 여전히 거부한다.

### totalCount 불일치

두 실제 응답 모두 행은 5개인데 totalCount=0이었다.
실제 원문을 바꿔 totalCount=5로 보정하거나 전체 건수로 추정하지 않았다.
Page 해독·부분 관측은 가능하지만, OfficialCollectionService의 완전 수집은
PAGE_TOTAL_INCONSISTENT로 명시적으로 중단하도록 했다.
완료 Cursor/Coverage·기존 데이터 갱신·Outbox를 만들지 않는 합성 DB 회귀를 추가했다.
제공기관에 이 endpoint의 totalCount 의미·페이지 정렬/완전성·첫 페이지와 전체 건수의 관계를 확인해야 한다.

### 저장·정규화·사건 판정

실제 응답은 실행별 owned_<UUID> 폐기용 PostgreSQL DB의 기존 collection_run/owned_collection_page에 저장했다.
raw_body를 다시 읽어 원래 바이트와 동일한지 확인했고, 별도 삽입을 rollback하여 기존 페이지 수가 유지되는지 검증했다.
최초/재검증 원문은 총 2페이지 저장 성공 후 테스트 DB와 함께 폐기했다. 운영 DB나 Git Fixture에 적재하지 않았다.
부분 표본은 FAILED/DEVELOPMENT_PARTIAL_PROBE로 표시하며 전체 수집 성공으로 위장하지 않는다.

정규화 가능한 필드·시각·식별 형식까지 검사했다.
실제 OfficialCollectionService.collect 전체 수집을 실행한 것은 아니다.
행수 모순·선행 경고 관계·공식 사건 연결·전체 지역 매핑이 미확정인 상태에서 실제 피해 사건을 등록하지 않았다.
disaster/source_disaster_revision/disaster_runtime_outbox/summary_runtime_task는 0을 유지했다.
REG_ID→ADM_ID 전체 집합·유효기간·서비스 코드 호환 계약이 없어 영향지역 UNKNOWN도 유지한다.
실제 정규화 사건·Revision·Outbox·AI 요약·인증 HTTP 사건 조회 성공으로 보고하지 않는다.

## 4. 지진 API허브 접근 계약

[지진/화산 API 문서](https://apihub.kma.go.kr/apiList.do?seqApi=7)는
getEqkMsgList/getEqkMsg/기간별 지진 조회를 개별 기능으로 표시하고 각 활용신청 UI와 authKey 계약을 제공한다.
[이용안내](https://apihub.kma.go.kr/apiInfo.do)는 회원 인증키·본인 사용, 회원유형/목적별 제공범위 차등을 설명한다.
[홈페이지](https://apihub.kma.go.kr/)는 연락처 미등록 시 이용 불가 안내를 표시한다.

공개 안내만으로 모든 개별 API가 별도의 관리자 승인을 반드시 요구한다고 단정하지 않는다.
회원가입 시 키 발급 안내와 각 API 신청 UI는 확인했지만, 해당 계정의 getEqkMsg 권한·신청 처리 상태는 확인하지 못했다.
이번에는 KMA_HUB_AUTH_KEY의 존재·빈 값 아님·제어문자 없음만 검사했다.
이 검사는 키 유효성·해당 API 승인·IP 허용·차단 상태 검증이 아니다.

5차 HTTP403의 가능한 원인과 근거:
- 계정/키·서비스 제공범위·회원유형/활용목적 제한: 공식 이용안내 근거, 계정 상태는 미확인.
- 연락처 인증/등록 요건: 홈페이지 안내 근거, 이 계정의 상태는 미확인.
- 해당 API 신청·키 상태: 각 API 신청 UI는 확인, 실제 처리 상태는 미확인.
- IP/보안장비/일시 차단: 403만으로 추론 가능한 운영 원인이며 해당 계정에 적용됐다는 공식 증거는 없음.
공개 문서로 특정 원인을 확정하거나 403을 데이터 없음으로 바꾸지 않았다.
개별 권한이 확인되지 않아 이번 Hub 데이터 요청은 0회다.

공공데이터포털 지진 가이드의 최근 자료 범위와 Hub 지진 자료 전체 보유기간은 서로 다른 계약이다.
계정 관리자/기상청에 정확한 getEqkMsg 경로, 호출 시각·HTTP403, 키 값 없이 계정/신청 상태,
연락처 요건·허용 네트워크·현재 과거 제공 범위를 확인하도록 요청한다.
실제 사건 ID·cnt 참조 관계·정정 순서가 없어 EARTHQUAKE_UNLINKED를 유지한다.

## 5. 공식 RAG 자료 확보·이용조건·최신성

[행정안전부 저작권정책](https://www.mois.go.kr/frt/sub/a08/copyrightPolicy/screen.do)은
개별 공공누리 표시 확인과 출처 표시를 안내하고, 미부착 자료는 담당자와 사전 협의하도록 설명한다.
이번 게시물 조사에서 개별 이용허락 표시를 확인하지 못했다.
이는 원문 전체에 표시가 절대 없다는 확정이 아니라, 현재 수집·가공·RAG/AI 사용 근거를 확보하지 못했다는 뜻이다.
사용자 지시에 따라 실제 본문 대량 적재·가공·운영 승인·이미지/PDF 재배포를 보류했다.

| 자료 | 등록일(발행일/버전과 구분) | 형식·확인 범위 | 이용조건/승인 |
|---|---|---|---|
| [지진 국민행동요령 소책자](https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000015&nttId=114774) | 2024-12-30 | 게시물 목차, 첨부 52쪽 PDF 표지/뒤표지 시각 점검. 텍스트 추출의 한글 깨짐 확인 | 개별 이용허락 미확인, METADATA_ONLY/PENDING |
| [호우·태풍 국민행동요령 포스터](https://www.mois.go.kr/frt/bbs/type002/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000530&nttId=117736) | 2025-05-15 | HTML 내 이미지 ALT 원문 존재, JPG 첨부 2개 | 이용조건 미확인, 본문 적재·승인 보류 |
| [산불 국민행동요령](https://www.mois.go.kr/frt/bbs/type002/commonSelectBoardArticle.do?bbsId=BBSMSTR_000000000205&nttId=116554) | 2025-03-25 | HTML 내 카드뉴스 ALT 원문 존재 | 이용조건 미확인, 본문 적재·승인 보류 |
| [국민안전24 통합 행동요령](https://www.safekorea.go.kr/safekorea-kor/acts/nacts/nationalActionTips.do?menuSn=4) | 개별 자료 날짜 미확인 | 동적 다중 재난 포털의 목록/참고 출처 | 개별 문서·버전·권리 범위 미확인, 참고 메타데이터만 보존 |

확인일은 2026-11-04이며 등록일·확인일만으로 최신 본문·실제 발행 버전을 보증하지 않는다.
후속 개정·PDF 텍스트 품질·문단 경계와 조건/부정어의 보존은 사람이 원문과 대조해야 한다.
원문 재배포 권한, 저장·문단 추출/가공, 서비스 응답 인용·AI 처리 조건의 근거가 필요하다.
실제 자료에 대한 사람의 APPROVE는 0건이며 임의로 승인하지 않았다.

## 6. 기존 RAG 도구 확장과 메타데이터

[PHASE6_SOURCE_CATALOG.json](../ai/resource/PHASE6_SOURCE_CATALOG.json)에 실제 출처 3개와 통합 참고 출처 1개를 기록했다.
본문·PDF·이미지·실제 안전 지시문은 포함하지 않았다.
sourceUrl/sourceTitle/publisher/disasterType/section·sourceLocator/registeredDate/checkedAt/sourceVersion/
approvalState/rightsState/contentFingerprint를 구분한다.
contentFingerprint는 아직 본문을 적법하게 추출·검증하지 않았으므로 null이다.
등록일/확인일 기반 sourceVersion은 추적용 메타데이터일 뿐 공식 발행본 버전이라는 주장이 아니다.

section 계획은 대비/발생 중/대피/대피 후/지원 필요 상황/금지행동 중 출처에서 확인 가능한 목차·ALT 위치를 가리킨다.
모든 문서에 모든 단계를 만들어 넣지 않았으며 실제 검색 문단으로 승인된 상태도 아니다.
지진 PDF는 원본 페이지별 대조, 포스터는 원래 ALT의 전체 조건·범위 확인이 추가로 필요하다.

기존 GuidanceAdminTool에 PLAN을 추가했다. 같은 ACTIVE 세션·OPS 권한을 요구하지만
네트워크·import·DB 저장·approve는 실행하지 않고 메타데이터만 검사한다.
카탈로그 sources의 항목 하나를 기존 run(bearer,Reader,Writer)에 전달하면 source/section 계획,
PENDING·runtimeSearchEligible=false·contentFingerprint=null과 planFingerprint를 반환한다.
planFingerprint는 정렬된 메타데이터의 해시이며 공식 본문 fingerprint나 승인 근거가 아니다.
본문 fingerprint/APPROVED/권리확정 상태를 계획 데이터에 넣어 승인을 위장하는 입력은 거부한다.
PLAN 검사는 official_resource와 ai_resource_review 행 수를 바꾸지 않음을 실제 테스트 DB에서 확인했다.
카탈로그 자동 로딩·시작 시 import·새 Controller/API는 추가하지 않았다.

기존 OfficialGuidanceImporter의 HTML 추출기에 IMG ALT 원문 보존을 추가했다.
선택한 element 내부의 접근성 텍스트만 완전한 문단으로 보존하고 외부 이미지 요청/OCR/요약·재작성은 하지 않는다.
ALT 없는 이미지에서 행동요령을 만들어내지 않는다.
공식 mois.go.kr 별칭을 정확한 허용 목록에 추가했으며 임의 도메인·인증 쿼리·userinfo·비HTTPS는 계속 차단한다.
실제 자료를 importer에 넣거나 자동 승인한 것은 아니다.

기존 DB의 source_url/title/agency_name/version_label/content_text/checked_at 및
owned_resource_import/ai_resource_review를 그대로 사용한다.
재난·section별 계획은 부속 메타데이터다. 스키마에 없는 계층을 DB에 있다고 가장하지 않는다.
권리 확보 후 실제 import 시 기존 revisionFingerprint와 원문 acquisition hash,
검토 evidenceReference·만료·철회·변경 승인 무효화를 그대로 사용한다. 이번 Migration 변경은 없다.

## 7. RAG·AI 답변 안전성 검증

실제 공식 자료 본문 적재/승인이 0이므로 실제 승인 corpus 검색 성공·공식 자료를 사용한 AI 응답 성공은 미검증이다.
자료를 임의 승인해 테스트를 통과시키지 않았다.
지진 실내/호우 지하차도/산불 대피 질문에 대한 실제 자료·실모델 품질 평가도 수행하지 않았다.

검증한 합성 경로:
- 재난별 한국어 검색·문단 구분·공식 기관/URL·버전·fingerprint·승인 기한 확인.
- ALT의 전체 조건·부정어 보존, 선택 영역 밖 ALT 제외, 원문 없는 이미지 차단.
- OPS PLAN 인증·권한과 실제 출처 메타데이터의 비적재/비승인.
- TEST ONLY ALT 문서를 폐기 DB에서 importer→미승인 검색 제외→합성 검토 Fixture→검색→ALT 변경 시 이전 승인 무효화.
  합성 검토 데이터는 운영 승인이나 실제 자료의 승인으로 사용하지 않았다.
- 잘못된 resource ID/미전달 문단/일부 문장 변형·조건 누락/미승인·철회·만료 자료/프롬프트 인젝션을 기존 테스트로 차단.
- 현재 위험도·대피 명령·실시간 사건/피해를 정적 RAG 문서만으로 답하지 않는 기존 정책 유지.
- 모델 timeout·부재·잘못된 JSON·재시도와 공식 원문/Revision 보존을 기존 HTTP 대역/통합 사례로 검증.

위험 답변·오인용 사례는 ChatRagIntegrationTest/OllamaChatAnswerGeneratorTest/ResourceServiceTest의
위조 ID, 선택되지 않은 문단, 근거 부족, 동적 위험 질문 등 회귀 사례로 관리한다.
실제 모델이 생성한 위험 답변을 수집했다는 뜻은 아니다.
실제 Ollama/GPU/외부 AI 호출은 0이며 기존 대역 성공을 실제 모델 품질로 보고하지 않는다.

## 8. GPU·Ollama 준비

[PHASE6_GPU_READINESS.md](../ai/ollama/PHASE6_GPU_READINESS.md)에 환경변수·모델 확인 GET·타임아웃·Worker/Chat 동시성·대기열·fallback,
VM↔GPU 내부망/TLS/인증·두 프로젝트 격리와 관리자 체크리스트를 정리했다.
현재 클라이언트는 원격 사설 IPv4 HTTPS와 정확한 allowlist만 허용하고 별도 proxy 인증 주입 기능은 없다.
Worker 단일 tick/DB lease가 프로젝트 전체의 GPU 동시성 제한은 아니다.
Chat 요청 공통 동시성 상한·GPU admission control도 아직 확인되지 않았다.

SSH 대상·계정·명시적 조회 허가가 없어 실제 서버 조회는 하지 않았다.
24GB와 프로젝트 약 8GB는 전달받은 요구이며 8GB 강제 격리·모델 적합성·실제 사용량은 미검증이다.
설치·모델 다운로드·로딩·추론·공용 환경변수·포트 공개·서버 재시작·타인 프로세스 조작은 0이다.

## 9. 테스트 결과

전체 회귀:
backend/gradlew.bat -p backend clean build --offline --no-daemon --console=plain
-PbuildOutputDir=C:/Users/dkemf/AppData/Local/Temp/eodiisseo-phase6-final-20261104
BUILD SUCCESSFUL, 2분 2초.
JUnit XML 총 1,032 = 통과 1,015 / 실패 0 / 오류 0 / 생략 17.
실호출 플래그는 모두 false, 운영 DB 대신 폐기용 PostgreSQL 사용.

주요 suite: OwnedRuntimeSeparationIntegrationTest 33, OfficialProviderClientTest 35,
OfficialGuidanceImporterTest 5, GuidanceSourcePlanTest 3,
ChatRagIntegrationTest 22, OllamaChatClientTest 37 모두 통과.
기존 인증·보안·개인정보 동의·알림·원문/Revision/Outbox·AI 재시도 회귀를 포함한다.

생략 17개는 통과로 세지 않았다:
실제 RAG 1, 실제 Ollama 3, Docker 조건의 AssignedDatabaseIntegrationTest 4,
FCM OAuth 1, 실제 자격증명 파일 기반 wiring 1, 실제 날씨 API 1, 재난 실검증 메서드 6.
비활성 parameterized 실검증 메서드 6개는 제공처별 사례 생성 전 XML에 6개로 집계된다.

별도 실제 호우 검증:
- 최초: eodiisseo-phase6-rain-live-20261104, 총 16 = 통과 4 / 실패 1 / 생략 11.
  조건부 시각 0을 필수 timestamp처럼 검사한 호환성 실패를 발견하고 담당 코드·테스트에서 보완했다.
- 최종: eodiisseo-phase6-rain-live-final-20261104, 총 16 = 통과 5 / 실패 0 / 생략 11, BUILD SUCCESSFUL.
  연결·실제 행 존재·필드 호환·저장/롤백·미확정 사건 차단이 통과했다.
- 생략 11: 범위 밖 지진/재난문자 각 5개 검사, 호우 실제 사건 확정 계약 1개.
- completeCountConsistent=false이므로 이 성공은 전체 수집/사건/운영 성공이 아니다.
두 실행은 각 1 GET이며 이후 추가 호출하지 않았다. 초기 실패도 숨기지 않았다.
결과 XML은 각 Temp 출력 디렉터리 test-results/test에 있으며 실제 원문/SN을 포함하지 않는다.

## 10. 변경 파일 (13개)

운영 코드 5개:
- disaster/collection/OfficialCollectionService.java
- disaster/collection/OfficialRecordNormalizer.java
- ai/resource/adapter/OfficialGuidanceImporter.java
- ai/resource/adapter/GuidanceAdminTool.java
- ai/resource/adapter/GuidanceSourcePlan.java (신규)

대응 테스트 5개:
- disaster/collection/OfficialDevelopmentLiveValidationTest.java
- disaster/collection/OfficialProviderClientTest.java
- disaster/OwnedRuntimeSeparationIntegrationTest.java
- ai/resource/OfficialGuidanceImporterTest.java
- ai/resource/GuidanceSourcePlanTest.java (신규)

문서·메타데이터 3개:
- disaster/PHASE6_INTEGRATION_REPORT.md (신규, 본 문서)
- ai/resource/PHASE6_SOURCE_CATALOG.json (신규)
- ai/ollama/PHASE6_GPU_READINESS.md (신규)

위 경로는 각각 backend/src/main/java/com/disasterinfo/eodiisseo/ 또는 대응 backend/src/test/java/com/disasterinfo/eodiisseo/ 기준이다.
타 담당자 기존 파일·기존 Migration·빌드/프론트엔드 diff 없음. git diff --check 통과.
시작 HEAD/브랜치 유지, staged 0개. Commit/Push 없음.

## 11. 운영 반영 전 남은 사항

1. KMA에 getPwnCd의 totalCount=0/5행 응답·페이지 완전성 계약을 확인해야 한다.
   9월 26일 첫 5행은 해제 자료이며 지정 제09-111호 자체 확보·선행 발효 관계도 별도 검증이 필요하다.
2. 공식 사건/발표 연결·전체 REG_ID/ADM_ID 대응·유효기간이 확보되기 전 실제 사건·영향지역·Revision/Outbox 자동화는 보류한다.
3. 지진 Hub 관리자가 해당 API 신청/키/연락처/접근 제한을 확인해야 하며 확인 전 재요청하지 않는다.
4. 행동요령 각 자료의 개별 공공누리 표시 또는 제공기관의 저장·가공·RAG/AI 사용 허락 근거를 확보해야 한다.
   권리 근거 확보 후 PDF/ALT 본문 위치·범위·최신 버전·fingerprint를 사람이 검토한다.
5. 적법한 본문 import→INSPECT→사람의 APPROVE 후 재난별 실제 검색·인용·철회/만료를 검증한다.
   현 카탈로그를 운영 corpus로 자동 import하거나 자동 승인하지 않는다.
6. GPU 관리자와 계정·설치/추론·내부망 인증·프로젝트 quota·VRAM 격리·모델·동시성을 합의한 후 별도 실환경 검증한다.
   실제 모델 품질과 공유 서버 영향은 아직 평가하지 않았다.
