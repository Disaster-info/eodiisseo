# 어디있어 운영 정상화 통합 보고서

## 현재 상태

기존 안정화 기준은 로컬 `af8c6e9`였다. 후속 명시 승인에 따라 `origin/develop`의 `056ff2e26d0ceeca7229888df85683f903a9127f`를 `a6eec6e`로 로컬 병합했다. OAuth 콜백 및 Cloudflare 관련 7개 파일을 반영했고 기존 백엔드 미커밋 변경을 보존했다. 운영 Pages에서 새 OAuth 흐름을 재검증하는 작업은 남아 있다.

공개 Cloudflare Pages 주소의 초기 HTTPS GET은 HTTP 200, 약 0.360초였다. 외부 API 연동, 브라우저 로그인, 화면 동작, Tailscale 미설치 여부는 이 응답으로 확인할 수 없다. 운영 OpenStack SSH 대상·접근 경로와 운영 테스트 계정은 확인되지 않았다. 보고된 운영 데이터·버전·장애는 요청서 기록이며 이번 작업에서 재측정한 운영 DB 결과는 없다.

## 장애별 조치 및 잔여 범위

| 장애 | 이번 상태 |
|---|---|
| OPS-01 | 운영 런타임 조회 어댑터에서 빈 목록의 LIVE 수집 상태와 Coverage를 검증한다. 미수집·실패·Coverage 미확인에는 새 503 오류 코드를 사용한다. 검증된 지진 범위의 빈 목록은 기존 200 응답이다. 활성화된 운영 어댑터와 기존 500의 로그 원인은 서버에서 확인해야 한다 |
| OPS-02/04 | 공식 행정구역의 검토된 JSON만 OPS API로 전송할 도구를 구현했다. 해시, 단일 원자 요청 크기, 중복 코드, 활성 시군구 경계 형태를 점검한다. 전국 행정구역·대피소 원본 승인과 실제 적재, 관심지역 및 경로 실검증은 보류 |
| OPS-03/06/07/15 | 지도 렌더링 조건, 소셜 연결, 그룹 안전 판정, 폰트의 담당 범위와 완료 기준을 전달 문서에 작성했다 |
| OPS-05 | 신규 계정 수명주기와 알림 기본값을 같은 트랜잭션에 연결했다. 기존 사용자용 기본값 보정은 preview가 기본이며 ACTIVE·누락 대상만 저장하고 기존 설정을 덮어쓰지 않는다. 운영 알림 저장소 활성화와 실제 보정은 승인 대기 |
| OPS-08/09/10 | 요청서의 순차 curl 401과 브라우저 499·nginx DNS 오류는 상관관계가 입증되지 않았다. 운영 로그와 공개 브라우저의 타이밍, 실제 upstream 수신, 날씨 캐시 검증이 필요하다 |
| OPS-11/12/13 | 요청서의 직접 GPU 생성 기록은 Spring RAG 생성의 근거와 구분한다. 기존 지진·호우·산불 후보 및 이용조건은 [이전 안정화 보고서](BACKEND_RUNTIME_STABILIZATION_REPORT.md)에 있다. 원문 권리, 운영 승인·색인, 공식 원문 및 테스트 계정 확보 뒤 실제 Spring 안내·요약을 검증해야 한다 |
| OPS-14 | FCM 활성화, 서비스 계정과 별도로 승인받은 테스트 기기, 실제 수신 검증이 남아 있다 |
| OPS-16 | 운영 Compose 다섯 파일을 사용하는 백업·검증·빌드·롤백 가이드를 준비했다. 요청서의 운영 SHA `fad9bdc`와 실제 이미지가 일치하는지는 운영 서버 확인 대상이다 |
| OPS-17 | 회원 잠금 아래 이벤트와 초기 기기 발송 계획을 원자적으로 기록한다. delivery가 없는 구형 이벤트와 인증 경계 전의 초기 계획을 재개한다. FCM 접수 불명 행은 자동 재발송하지 않는다 |
| OPS-18 | 영속 Worker는 짧은 트랜잭션에서 claim·대상 확인, 트랜잭션 밖의 모델 호출, 저장 시 claim·Lease·Revision 재확인 순서로 처리한다 |

## 운영 데이터 및 승인

요청서 기준 `admin_area`·`disaster`·`collection_run`·`notification_setting`은 0건이며 `shelter` 30건, `official_resource` 3건, 승인 0건, FALLBACK 6건이다. 운영 DB 쓰기, 환경 업데이트 및 컨테이너 조작에 대한 별도 실행 승인이 없으므로 적재 전후 비교와 실제 기능 검증은 미실행이다. 상세 진단은 운영자용 읽기 전용 SQL로 준비했다. 일반 사용자용 진단 API 추가는 없었다.

| 데이터 제공처 | 운영 검증 상태 |
|---|---|
| WEATHER_API | 요청서의 관측 2건 기록. 캐시·재갱신·실제 기상 API 요청은 미확인 |
| KMA_EARTHQUAKE | LIVE 수집 이력 0건 보고. Portal·Hub 사건 참조·증분·접근 계약 확인 필요 |
| KMA_WARNING | 특보 목록 투영과 증분 계약, 호우 영향지역 코드의 시점별 매핑·전체 수 모순 확인 필요 |
| SAFETYDATA_DISASTER_MESSAGE | 재난문자 증분과 사건 연결 권한·계약 확인 필요 |
| SAFETYDATA_EARTHQUAKE_SHELTER | 기존 시설 30건 보고. 전국 이용 승인·페이지·비활성 계약 및 좌표 검증 필요 |
| MOIS_WILDFIRE | 공식 사건 ID·발표 순서·상태 계약 확인 필요. 운영 어댑터 비활성 |

운영 승인, 실제 응답 형식과 페이지·호출 한도 등에 대한 검토에는 [기존 제공처 계약](backend/docs/collection)과 최신 권한 증거가 필요하다. 기존 차단 상태를 변경하지 않았다. Qwen 기본 모델과 로컬 GPU 터널의 구조는 요청서 기록을 사용했다. 이번 작업의 실제 GPU 장문·동시 요청·임베딩, 토큰과 VRAM, 실제 RAG/FCM/요약 성능 수치는 확보하지 못했다.

## 검증 및 변경 범위

조회는 결과의 유무에 관계없이 수집 범위를 검증한다. 행이 있는 목록의 기존 지역 일괄 조회를 유지한다. 일반 재난 100건 201→2 SQL은 이전 폐기용 DB의 지역 조회 측정값이다.

- 백엔드 재난·대시보드 영역에서 `OfficialDisasterQueryService.java`, `DisasterOperationalAdapters.java`, `DashboardException.java`, `DashboardHttpErrors.java`를 수정했다.
- 회원·알림 경계에서 `AuthService.java`, `MemberCreated.java`, `NotificationRetentionService.java`를 변경했다. 알림 저장소를 운영 설정으로 활성화하기 전에는 가입 알림 설정을 별도 저장하지 않는다.
- `OwnedRuntimeSeparationIntegrationTest.java`와 `DashboardHttpErrorsTest.java`에 초기화·롤백, 실제 HTTP 수집 상태와 503 응답 회귀를 추가했다.
- `backend/scripts/database`의 `production-readiness.sql`, `initialize-notification-settings.sql`, `Import-VerifiedAdministrativeAreas.ps1`를 신규 작성했다.
- 프런트엔드, 운영 Compose, 기존 Flyway Migration은 변경하지 않았다.

로컬 폐기용 PostgreSQL에서 보정 SQL의 preview 1건, 적용 1건, 재적용 0건을 확인했다. 기존 설정과 탈퇴 대기 회원을 보존했다. 운영자 진단 SQL의 선택 스키마 미설치 분기와 행정구역 도구의 해시 고정 dry-run을 확인했다.

| 실행 | 전체 | 통과 | 실패·오류 | 생략 |
|---|---:|---:|---:|---:|
| 격리 복사본 전체 기본 `gradlew build` | 1,804 | 1,340 | 0 | 464 |
| 폐기용 PostgreSQL 런타임·알림·대시보드 집중 회귀 | 240 | 238 | 0 | 2 |
| PostgreSQL 17.11 Audit의 pgvector·RAG HTTP 집중 회귀 | 76 | 76 | 0 | 0 |

두 빌드 모두 성공했다. 기본 실행의 생략에는 DB 및 실제 외부 서비스 옵트인이 포함된다. PostgreSQL 집중 실행의 2건은 실제 자료 적재와 사람이 검토한 원문의 검색·철회 옵트인이다. 전체 PostgreSQL 스위트의 이전 1,919건 통과는 안정화 작업의 기준 결과이며 이번 실행 건수와 구분한다. 집중 회귀에서 가입 초기화 및 트랜잭션 롤백, 미수집·실패·범위 미확인 503, 최신 Coverage를 가진 지진 범위의 빈 목록 200을 확인했다.

기존 PostgreSQL 17.11 트리를 재사용해 55433 Audit에 pgvector 0.8.7을 활성화했다. 임시 벡터의 코사인 유사도와 HNSW 생성을 확인하고 임시 변경은 롤백했다. Audit에서 생성한 별도 테스트 DB의 실제 벡터 SQL 사례를 포함해 벡터 통합 37건과 RAG HTTP 39건이 모두 통과했다. 개발 DB 55432에는 접속하지 않았다. PostgreSQL 설치 스크립트의 헤더 추출 누락과 수동 복구·재현 절차는 [Windows pgvector 가이드](backend/docs/windows-pgvector.md)에 기록했다. 운영 DB의 확장 가용성과 실제 임베딩 모델 검증에는 별도 접근이 필요하다.

## 최종 판정

이번 변경은 개발 검증용이다. 운영 배포와 실제 기능 검증에는 OpenStack 접근, 변경별 승인, 공식 데이터 계약과 자료 사용 권리, 테스트 계정·기기 및 담당자 검수가 필요하다. [운영 배포 실행 가이드](PRODUCTION_DEPLOYMENT_RUNBOOK.md) · [담당자별 전달사항](PRODUCTION_INTEGRATION_HANDOFF.md)

## 후속 검증과 개발 DB 적용 범위

- **개발 DB 백업**. `55432/eodiisseo_local`의 읽기 전용 custom dump는 `%LOCALAPPDATA%\eodiisseo\dev-backup-20261124-125453\eodiisseo_local.dump`에 있다. 크기 175,779바이트, SHA-256 `84D4CAAD785C4F7583383AB20678D7CC067F168659F256006A435B55BC9E1E9E`다. 아카이브 목록을 읽고 SHA를 다시 검증한 후 55433의 별도 DB에 전체 복원했다.
- **Flyway**. 복원본의 기존 이력은 `2026102701`~`2026102703`이며 PostGIS 3.6.2와 pgvector 0.8.7을 보존했다. canonical·owned-runtime 위치에서 기존 이력을 검증한 뒤 `2026110201`, `2026110301`~`2026110304`, `2026111001`을 적용했다. JPA 스키마 검증과 앱 기동이 성공했고 회원 정보·재난·알림·AI의 익명 요청은 401이었다.
- **실제 개발 DB 적용 전 확인**. 기존 앱 계정의 로컬 설정 후보 두 개는 인증에 실패했다. 관리 계정의 읽기 전용 조회에서 앱 역할에 public 스키마 CREATE 권한이 있음을 확인했으나 복원 실행은 Audit 관리자였다. 실제 개발 DB의 역할·자격 확인과 적용 승인까지 Flyway를 실행하지 않는다. 가이드의 기본 실행은 Flyway와 외부 연동을 비활성화한다.
- **FCM 복구 경계**. 초기 재시작은 새 방식으로 계획된 행 가운데 제출 허가 전 PENDING만 처리한다. 제출 허가 뒤 중단, 구형의 접수 여부 불명 PENDING 및 기존 재시도 시도의 접수 불확실성을 자동 재발송하지 않는다. 구형 부분 발송 기록에서 원래 기기 집합을 추정하지 않는다.
- **요약 경계**. 기본 영속 Worker 경로의 생성 중 DB 트랜잭션을 해제했다. 이어받은 Worker와 기존 Worker의 외부 연산이 Lease 만료 지점에서 겹칠 수 있지만, 기존 claim의 결과는 저장할 수 없다. 구형 직접 호출 어댑터 호환 경로의 advisory 트랜잭션은 유지한다.
- **재난 최신성**. 공식 데이터가 있는 목록에도 최신 LIVE 수집, 당일 Coverage·Cursor 및 최신 Revision 일치를 목록 ID 전체에 대해 일괄 검사한다. 불확실한 자료는 기존 공식 사실을 표시하고 `STALE_OR_UNVERIFIED`를 반환한다. 전체 유형의 범위를 증명할 수 없으면 `PARTIAL_COVERAGE`다. 목록의 지역 조회는 기존 배치를 쓰며 전체 HTTP 경로에는 Coverage SQL 한 번이 추가된다. 이전 201→2 지표와 이번 전체 경로는 구분한다.
- **GPU 터널·Bash**. 배포와 롤백에서 백엔드와 터널을 함께 재생성하고 네임스페이스 inode를 대조하도록 가이드를 수정했다. Bash 블록 7개는 `bash -n`을 통과했다. 운영 Compose에서 실제 실행은 미검증이다.
- **PostgreSQL 설치 스크립트**. 기존 PostgreSQL ZIP에 포함된 `include/server/postgres.h`가 추출되도록 허용 목록을 확장했다. 기존에 구축한 설치와 pgvector를 재사용했으며 실제 파일 교체 없이 ZIP 항목 선택과 PowerShell 문법을 검증했다.

### 전체 Audit 테스트 발견·생략 차이

| 실행 | 발견 | 통과 | 실패·오류 | 생략 |
|---|---:|---:|---:|---:|
| 이전 PostgreSQL 17 Audit 전체 | 1,952 | 1,919 | 0 | 33 |
| 이번 PostgreSQL 17.11/PostGIS 3.6.2/pgvector 0.8.7 Audit 전체 | 1,956 | 1,924 | 0 | 32 |

동일 집계 방식인 JUnit XML 기준이다. 신규 발견 4건은 재난 HTTP 오류 매핑 enum 사례 3건과 알림 초기 계획 복구 사례 1건이다. 기존에 생략됐던 `realPgvectorExactSqlWhenExtensionIsAvailable`이 실행되어 통과가 총 5건 증가했다. 나머지 생략은 실제 AI 4건, Docker DB 4건, 공식 API·경로 9건, 자료 승인 2건, 분리 TLS 11건, FCM 자격 증명 2건이다. Gradle 콘솔의 발견 수는 비활성 클래스 표시방식이 달라 이전 기본 실행의 XML 합계와 직접 비교하지 않는다. [전체 Audit 결과](backend/build/runtime-stabilization-audit/after/backend/build/runtime-final-audit/reports/tests/test/index.html)

전체 실행 이후 데이터가 있는 목록의 `LATEST`·`PARTIAL_COVERAGE`, 모델 호출 시 트랜잭션 부재 및 대체 claim 이후 결과 거절을 추가 단언했다. 이 단언을 포함해 재난 조회, 요약, FCM 초기 계획의 PostgreSQL 집중 테스트 3건이 통과했다. 알림 회귀는 커밋된 이벤트·delivery 상태에서 저장소를 다시 호출하는 방식으로 검증했고 OS 프로세스를 강제 종료하거나 실제 FCM을 호출하지 않았다. 이미 DONE 처리된 구형 Outbox 이벤트의 별도 일괄 복원은 이번 경로에 포함되지 않는다.

### 공식 자료 적재 준비

행정안전부 공개 카탈로그의 [시도 법정경계](https://www.safetydata.go.kr/disaster-data/view?dataSn=899)와 [시군구 법정경계](https://www.safetydata.go.kr/disaster-data/view?dataSn=900)를 확인했다. 공식 배포본의 좌표계 원문과 변환·재사용 권한을 확인할 수 있을 때까지 OPS 적용 파일은 생성하지 않았다. [국가데이터처 SGIS 경계](https://www.data.go.kr/data/15129688/fileData.do)는 PRJ 제공을 명시한 비교 후보이며 통계용 행정코드를 기존 법정구역 및 TMAP 코드에 그대로 대입할 근거가 없다. 운영 적재 준비는 원본 묶음의 SHA-256, 동일 판의 PRJ·코드표·메타데이터, Polygon 검증, WGS84 좌표 대조, 변경·폐지 코드 목록, OPS 입력 해시 순서로 진행한다. 대피소·재난 제공처 실호출과 AI 자료 사용 승인은 기존 차단 사유를 적용한다.
