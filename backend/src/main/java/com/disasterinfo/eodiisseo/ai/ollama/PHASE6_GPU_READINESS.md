# 6차 공용 GPU·Ollama 배포 사전 점검

확인일 2026-11-04(KST), 기준 HEAD a87c4bd, feature/ai-dashboard-noti.
이 문서는 코드·공식 문서 점검 결과와 관리자 확인 목록이다. 서버 배포·실기동·성능/메모리 검증 결과가 아니다.
SSH 대상·계정·읽기 전용 접근 허가가 이 세션에 제공되지 않아 접속 및 상태 조회는 0회다.
설치·모델 다운로드/로딩·추론·공용 설정 변경·프로세스 종료·포트 공개·서버 재시작을 하지 않았다.
사용자가 전달한 총 VRAM 24GB/프로젝트 약 8GB는 요구 조건이며 실제 격리나 보장 수치로 사용하지 않는다.

## 현재 프로젝트 연결 계약

| 설정 | 현재 코드의 의미 | 운영 준비 조건 |
|---|---|---|
| EODIISSEO_AI_OLLAMA_ENABLED | 명시적 true일 때만 실제 Chat/Summary Port 등록, 기본 비활성 | 관리자 승인·네트워크·모델 준비 후 별도 활성화 |
| EODIISSEO_AI_OLLAMA_BASE_URL | 기본 http://127.0.0.1:11434 | 승인된 전용 경로. 임의 DNS/공개 호스트는 현재 설정에서 불가 |
| EODIISSEO_AI_OLLAMA_ALLOWED_HOSTS | 기본 loopback 세 주소 | 사설 IPv4 literal을 정확히 허용해야 원격 연결 가능 |
| EODIISSEO_AI_OLLAMA_MODEL | 기본값 없음, 명시적 모델 식별자 필요 | GPU 서버에 실제 설치된 정확한 이름/태그 확인. 현재 미선정 |
| EODIISSEO_AI_OLLAMA_REQUEST_TIMEOUT | ISO-8601 양수 Duration 필수, 기본값 없음 | Chat 전체 deadline·proxy timeout·큐 대기까지 포함해 합의 |
| EODIISSEO_AI_OLLAMA_CONNECT_TIMEOUT | 기본 PT5S | VPN/내부망 TLS 연결 지연을 측정한 뒤 검토 |
| eodiisseo.ai.summary.max-retries | 기본 0, ConfiguredSummaryRetry | 비용·장애 정책 승인에 따라 운영자 결정 |
| eodiisseo.ai.summary.retry-delay | 기본 PT30S | 서버 과부하 시 무제한 즉시 재시도 금지 |
| eodiisseo.runtime.enabled | 기본 false | 영속 Worker를 이번 작업에서 활성화하지 않음 |
| eodiisseo.runtime.poll-ms | 기본 1000 | 원문 이벤트·요약 처리 지연과 합의 |

이 값들은 코드 기본값/계약이다. 실행 중인 서버의 실제 환경값을 조회하거나 변경한 것이 아니다.
근거 코드는 OllamaSettings, OllamaConfiguration, OllamaAdapterBeans, OllamaChatClient,
ConfiguredSummaryRetry, DisasterRuntimeWorker, DurableSummaryTasks, ChatDeadline이다.

현재 OllamaChatClient는 /api/chat에 stream=false, temperature=0.2, num_ctx=4096을 보낸다.
요청 65,536바이트·응답 1MiB·생성 content 65,536바이트 상한, body 수신까지 timeout/deadline 적용,
자동 proxy/redirect 없음, 외부 인증 토큰·본문/URI 로깅 없음.
이 제한이 프로젝트 전체 GPU 동시성 또는 VRAM 한도는 아니다.

## 모델·상태 확인

[공식 /api/tags](https://docs.ollama.com/api/tags)는 설치 모델 목록 조회다.
기존 verifyInstalledModel()은 이 GET 결과에서 설정 모델의 정확한 이름을 검사하며 자동 pull을 하지 않는다.
[공식 /api/ps](https://docs.ollama.com/api/ps)는 현재 로딩된 모델과 size_vram 등을 확인하는 읽기 API다.
설치 목록·로딩 상태·모델의 메모리 실측은 서로 다른 검증 항목이다. 이번에는 모두 호출하지 않았다.
모델을 고르거나 새 모델이 8GB 안에 들어간다고 추정하지 않는다.

관리자에게 SSH 조회 권한이 제공된 뒤 허가된 범위에서만 실행할 읽기 목록:
- whoami, id
- nvidia-smi
- command -v ollama, ollama --version
- ollama ps
- systemctl status ollama (조회만, 권한이 허용되는 경우)

조회 출력에는 다른 사용자 작업 정보가 포함될 수 있으므로 공유 범위도 먼저 합의한다.
ollama run/serve/pull, 모델 사전 로딩용 POST, 서비스 재시작·환경변수 변경은 이 목록에 포함하지 않는다.

## VM ↔ GPU 네트워크·인증

Spring/PostgreSQL/검색은 VM, 추론은 GPU 서버에 둔다는 계획만 확인했다.
현재 클라이언트는 loopback의 HTTP/HTTPS 또는 명시 허용한 사설 IPv4 literal의 HTTPS만 허용한다.
공인 IP·일반 DNS·원격 plain HTTP, userinfo/query/fragment가 있는 base URL은 거부한다.
내부 HTTPS 인증서의 IP SAN·JVM 신뢰가 맞는지는 미확인이다. TLS 검증을 끄는 우회는 하지 않는다.

[Ollama 공식 인증 문서](https://docs.ollama.com/api/authentication)는 로컬 API가 별도 인증을 요구하지 않는다고 설명한다.
공유 서버 API를 외부에 바로 공개하는 배포안은 채택하지 않는다.
관리자와 전용 내부망/VPN·방화벽의 VM egress allowlist·TLS reverse proxy 구성을 협의해야 한다.
현재 Java 클라이언트에는 proxy용 Bearer/basic/mTLS 인증 주입 기능이 없다.
따라서 관리자 정책이 이런 인증을 필수로 요구하면 담당 AI 어댑터의 별도 승인된 확장 또는 승인된 loopback 터널 구성이 선행돼야 한다.
임시로 인증을 없애거나 공통 보안 파일을 수정하지 않는다.
loopback 터널도 실제 SSH 계정·접근 권한·프로세스 소유권·전용 경로가 승인된 후 검토할 대안일 뿐 이번에 만들지 않았다.

## 동시성·대기열·두 프로젝트 격리

현재 Worker tick은 단일 bean에서 이벤트 1건과 요약 1건을 순서대로 처리한다.
DB claim은 FOR UPDATE SKIP LOCKED와 20분 lease로 중복 작업을 막는다.
여러 애플리케이션 인스턴스는 서로 다른 Revision을 동시에 처리할 수 있으므로 전역 GPU 1슬롯 보장이 아니다.
실시간 Chat은 request-local virtual task와 deadline을 사용하며, 프로젝트 공통 추론 세마포어·영속 Chat 대기열·엄격한 동시 요청 상한은 현재 코드에 없다.
요약 대기열은 summary_runtime_task의 PENDING/WORKING/HELD 상태다.
설정된 재시도와 Worker HELD 재확인은 있으나, 무한 적체를 막는 운영 수량 한도·프로젝트 간 공정성·GPU admission control은 별도 협의가 필요하다.

[Ollama 공식 FAQ](https://docs.ollama.com/faq)에 문서화된 OLLAMA_NUM_PARALLEL,
OLLAMA_MAX_LOADED_MODELS, OLLAMA_MAX_QUEUE, OLLAMA_CONTEXT_LENGTH, OLLAMA_KEEP_ALIVE는 서버 범위의 설정이다.
병렬도·컨텍스트·모델 상주가 메모리와 큐 동작에 영향을 준다는 자료이며, 프로젝트별 8GB 강제 격리의 증거가 아니다.
이 값들을 이번 작업에서 설정하거나 기존 공용 서버의 값을 추정하지 않았다.

관리자 검토안:
- 두 프로젝트의 시스템 계정·접근 경로·요청 인증·로그·quota를 구분한다.
- 가능하면 별도 서비스 인스턴스와 승인된 자원 격리를 검토하되, 같은 GPU 사용만으로 VRAM이 분리된다고 주장하지 않는다.
- 공용 단일 인스턴스라면 gateway에서 프로젝트 식별, 동시 요청·대기열 상한, 공정성, timeout/과부하 응답을 합의한다.
- 다른 프로젝트 요청 때문에 모델이 교체되거나 queue가 길어지는 경우의 비용·지연·개인정보 정책을 결정한다.
- 모델 파일·프롬프트·키·DB/로그 디렉터리와 운영 책임자를 구분한다.
- “오늘 뭐 했어?” 프로젝트 코드를 변경하거나 설정을 읽어 자원 사용 권한을 추정하지 않는다.

## 장애·운영 검증 조건

기존 요약은 영속 작업과 실패/재시도 상태를 사용하고, 공식 원문·Revision 저장을 AI 성공에 종속시키지 않는다.
Chat은 근거/승인/동의 부족, 잘못된 모델 출력, timeout·모델 부재 시 FALLBACK/INSUFFICIENT 경로로 처리한다.
모델이 작성한 새로운 대피 명령·현장 위험도·경로는 공식 사실로 취급하지 않는다.
기존 HTTP 대역 테스트는 전송·오류·근거 검증을 확인하지만 실제 GPU 처리량·메모리·한국어 모델 품질을 보장하지 않는다.

배포 전 관리자 확인:
- [ ] SSH 대상·계정·허용 명령·조회 정보의 공유 범위
- [ ] 실제 GPU 종류·사용 중인 다른 작업·프로젝트 자원 계약
- [ ] 8GB 요구를 강제할 수 있는 격리/입장 제어와 초과 시 동작
- [ ] 설치·서비스 실행·모델 다운로드/로딩·추론의 각각 별도 권한
- [ ] 허용 모델·태그·버전·라이선스·한국어 안전 평가 자료
- [ ] VM→GPU 내부 주소·접근 제어·인증·TLS 신뢰·타임아웃
- [ ] 두 프로젝트의 요청 상한·queue·공정성·장애/점검 책임
- [ ] 실측 계획: idle/모델 로딩/동시 요청별 VRAM, 지연·오류·재시도, 다른 사용자 영향
- [ ] 공식 RAG 재이용·AI 처리 조건과 사람의 fingerprint별 승인
- [ ] 개인정보 최소화·외부 처리 동의·보존·감사 정책

이번 상태: 코드 연결 지점 및 준비 문서는 작성 완료. 실제 GPU 서버 상태·실모델 연동·VRAM 격리·운영 배포는 미검증이다.
