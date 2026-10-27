# 공식 행동요령 검색 계약

`ResourceService`가 기존 `ChatOfficialResourceSearchPort`를 구현한다. 개인정보 최소화와 외부 전달 승인은 기존 `ChatService`가 먼저 수행한다. 검색 결과는 승인된 원문의 완전한 문단만 담고 기존 Ollama 생성기의 근거 ID와 문단 일치 검증을 그대로 거친다. 요약 도메인은 변경하지 않는다.

## 저장과 검색

`JdbcOfficialResourceStore`는 ERD 4.2.1의 `official_resource` 7개 속성만 사용한다 (`resource_id`, `agency_name`, `title`, `source_url`, `version_label`, `content_text`, `checked_at`). 준비된 SQL 파라미터와 PostgreSQL `position()`으로 최대 60건을 조회하므로 사용자 입력의 `%`, `_`, 따옴표가 와일드카드나 SQL로 실행되지 않는다. 기존 테이블에 별도 검색 컬럼과 인덱스를 요구하지 않으며 전체 스캔 비용은 자료 증가 시 점검해야 한다.

문서 모델을 전역 JPA Entity로 추가하지 않았다. 기존 다른 도메인의 최소 FK 테스트 테이블과 스키마 검증에 영향을 주지 않고, 이미 있는 DataSource와 Spring JDBC로 실제 PostgreSQL 저장·조회를 수행한다. 새 테이블, 컬럼, Migration, 공통 설정, 의존성을 추가하지 않는다. DataSource가 없으면 저장을 가장하지 않는다.

저장은 INSERT ONLY이며 같은 UUID의 완전히 동일한 재발행만 허용한다. 원문·기관·제목·URL·버전·확인 시각이 바뀌면 새 UUID로 발행해야 과거 `ai_answer_resource`의 근거가 보존된다. PostgreSQL 시각 정밀도에 맞춰 확인 시각을 마이크로초로 정규화한다. 원문 내용과 나머지 메타데이터는 변형하지 않는다.

`ResourceParagraphSearch`가 향후 embedding/pgvector 교체 경계다. 현재 구현은 지진·호우·산불 한국어 동의어 및 상황어를 이용한다. 재난 종류가 여러 개이거나, 동적 위험·발생 여부·경로·피해 예측·지원되지 않는 질문은 빈 결과로 처리한다. 원문의 공백과 내부 줄바꿈을 보존하며 빈 줄로 나뉜 완전한 문단만 문서당 최대 3개, 최대 3개 문서에서 제공한다. 질문의 각 상황어에 대응하는 문단이 부족해도 FALLBACK이다. 문서에 다른 재난 유형이 명시된 문단은 제외한다. 범용 의미 검색 품질이나 재난 상황의 실시간 판정을 보장하지 않는다.

질문의 구체적 대상·동작 단어를 보존하고 선택 문단에서 모두 확인한다. 지원하지 않는 수영·조리 등 질문을 단순히 재난 단어가 있다는 이유로 답하지 않는다. 제목이 여러 재난을 포함한 자료는 현재 ERD에 절 계층과 문단 소속 정보가 없으므로 사용하지 않는다. 일반 안내 문단도 재난별 문맥이 확인되어야 한다. 형태소 분석기가 없는 보수적 검색이므로 표현 차이와 복합 질문에서 거짓 음성(FALLBACK)이 발생할 수 있다.

## 명시적 검토와 최신성

공식 호스트만으로 자료를 승인하지 않는다. `ResourceApprovalPort`의 명시적 승인에는 자료 UUID, 전체 저장 속성의 SHA-256 fingerprint, 검토 시각, 재확인 기한, 공개 자료 외부 AI 전달 승인이 필요하다. 검토자는 기관·원문·이용허락범위·프롬프트 인젝션 여부를 확인해야 한다. 승인 또는 정책 구현이 없거나 기한이 지났거나 자료가 변경되면 근거를 제공하지 않는다. 이 작업에서 운영 기본 만료일이나 자동 갱신 주기를 임의로 정하지 않는다.

승인 공급자는 현재 발행본만 반환하고 대체된 ID의 승인을 철회해야 한다. 한 URL에 서로 다른 승인 ID가 함께 검색되면 최신 버전을 추정하지 않고 FALLBACK한다. 운영 승인 공급자가 없는 현 상태의 FALLBACK은 의도된 제한이다. 실제 원천 조사 결과는 [공식자료 원천 검토](OFFICIAL_SOURCE_REVIEW.md)에 기록한다.

초기 출처 허용 범위는 기관명 `행정안전부`와 정확히 일치하는 국민안전24·국민재난안전포털·행정안전부·재난안전데이터공유플랫폼 HTTPS 호스트다. 사용자정보·임의 포트·fragment·유사 도메인은 허용하지 않는다. 다른 공식기관 확장은 승인된 출처 정책과 검증 후 진행한다.

운영 승인 구현, 공식자료 발행, 자동 수집기는 제공하지 않는다. 테스트의 가상 문서와 가상 승인은 운영 자료로 자동 등록하지 않는다. 실제 공식자료 확보와 이용조건 확인 현황은 작업 최종 보고를 따른다.

## 기존 채팅 연결과 운영 선행조건

Spring이 `ResourceService`를 기존 검색 Port로 주입하며 `ChatService → ChatOfficialResourceSearchPort → OllamaChatAnswerGenerator` 흐름을 재사용한다. 생성기에는 검색한 문단과 자료 UUID만 제공한다. 모델이 반환한 UUID와 인용문은 실제 전달한 자료와 완전한 원문 문단에 대응해야 한다. API-CHAT-001의 응답 DTO, 기존 요약 생성 구조와 Ollama 전송 구현은 변경하지 않았다. 정적 행동요령으로 현재 재난 발생, 위치별 위험, 대피소 가용성, 안전 경로를 판단할 수 없다는 생성 지침을 추가했다.

현재 소스에는 `ChatAuthenticationPort`, `ChatPromptPrivacyPort`, `ChatRetentionPolicy`의 운영 구현이 없다. 이 작업에서 그 절차를 대체하거나 우회하지 않았다. 운영 채팅을 열려면 기존 인증·로컬 개인정보 최소화 및 외부 전달 승인·보존정책 공급자를 연결하고, 이용조건과 원문 검토를 마친 현재 발행본의 `ResourceApprovalPort`를 제공해야 한다. DataSource와 ERD의 기존 테이블도 준비되어야 한다. 테스트에서 사용하는 인증·개인정보 승인·보존정책은 테스트 전용이다.

다른 API의 실제 Client와 응답 DTO는 현재 골격이다. API별 실제 URL, 키 환경변수 차이, 파라미터, DTO, 주기, 오류, 최신성, 팀원 Port 및 이벤트 제안은 [외부 API 계약 점검](EXTERNAL_API_CONTRACT_REVIEW.md)에 기록했다. 타 도메인의 수집·서비스·Repository·설정은 변경하지 않았다.

## 별도 변경 제안과 잔여 한계

현재 텍스트 검색에는 DB 변경이 필요하지 않다. 향후 embedding/pgvector 도입 시 문단 식별·문맥·원문 fingerprint, embedding 모델과 버전, 벡터 저장 및 인덱스 설계를 별도 검토해야 한다. 이 작업에는 Migration이 없다. 출처 URL을 응답에 추가하려면 API-CHAT-001의 `sources` 확장을 별도 API 변경으로 합의해야 하며, 현재 응답은 기존 기관명과 자료명 계약을 유지한다.

현재 검색은 세 재난과 제한된 상황어에 대한 결정적 텍스트 검색이다. 실제 공식자료 corpus의 정답셋으로 precision/recall을 측정하지 않았다. 조사·띄어쓰기·동의어·복합 질문 때문에 근거가 있어도 FALLBACK할 수 있다. 후보 60건 제한과 기존 컬럼의 텍스트 전체 스캔은 대규모 자료에서 성능 및 검색 누락을 평가해야 한다. 검토 유효기간과 대체 발행본 철회는 승인 공급자의 책임이며, 문단 원문 일치가 자연어 질문과 답변의 모든 의미 관계를 증명하지는 않는다.

## 변경 파일

프로덕션 코드는 이 패키지와 `../chat/port/ChatAnswerGenerationPort.java`로 한정한다.

| 파일 | 변경 내용 |
| --- | --- |
| `ResourceService.java` | 기존 검색 Port 구현, 출처·승인·버전·문단 검증 |
| `adapter/JdbcOfficialResourceStore.java` | 기존 ERD 저장·조회 및 제한된 파라미터 텍스트 검색 |
| `adapter/KoreanLexicalParagraphSearch.java` | 한국어 질문·상황·구체 단어의 문단 검색 |
| `entity/OfficialResourceDocument.java` | ERD 7개 속성과 원문 fingerprint |
| `port/OfficialResourceStore.java` | INSERT ONLY 저장·조회 계약 |
| `port/ResourceApprovalPort.java` | 현재 발행본 검토·전달 승인·재확인 기한 계약 |
| `port/ResourceParagraphSearch.java` | 검색 구현 교체 경계 |
| `../chat/port/ChatAnswerGenerationPort.java` | 동적 사실 추정 금지 생성 지침 |
| `README.md`, `OFFICIAL_SOURCE_REVIEW.md`, `EXTERNAL_API_CONTRACT_REVIEW.md` | 구현·검증·실자료 상태와 외부 연결 계약 |

테스트는 `src/test/java/com/disasterinfo/eodiisseo/ai` 아래의 `resource/ResourceServiceTest.java`, `resource/ResourceSpringContextTest.java`, `resource/OfficialResourcePostgresTest.java`, `resource/ResourcePostgresFixture.java`, `chat/ChatRagIntegrationTest.java`, `chat/ChatRagLiveIntegrationTest.java`를 추가했다.

## 최종 검증 결과

2026-10-27 KST에 `backend/gradlew.bat build --rerun-tasks --no-daemon --console=plain`을 실행했다. **594개 성공, 실패 0, 오류 0, 건너뜀 0**, 전체 빌드 1분 9초였다. 기존 테스트 527개와 신규 67개가 모두 실행됐다. 테스트 실행 중에만 명시적 폐기용 PostgreSQL 환경과 로컬 Ollama 실모델 검증을 활성화했다.

| 신규 검증 | 개수 | 실제 사용한 구성과 결과 |
| --- | ---: | --- |
| `ResourceServiceTest` | 39 | 가상 문서·검토 승인으로 한국어 유사 질문, 호우/산불, 구체 질문 불일치, 문단 후순위 검색, 복합 재난, 빈 결과, 출처 위조, 자료 변경, 승인 만료, 인젝션, 중복 버전 검증 |
| `ResourceSpringContextTest` | 1 | 실제 Spring 검색 Port 연결, DB·승인 미구성 상태의 안전한 빈 결과 |
| `OfficialResourcePostgresTest` | 4 | 별도 로컬 PostgreSQL 18의 임시 스키마, 기존 7개 컬럼 저장·조회, 원문/메타데이터 보존, 변경 UUID 거부, 파라미터 검색, DB 변조 시 승인 무효화 |
| `ChatRagIntegrationTest` | 22 | 실제 검색기·ChatService·Ollama 생성 검증기와 Fake 모델 전송/인증/저장 연결; 근거 ID 위조·미전달 문단·근거 부족·외부 전달 거부·동적 위험 질문 검증 |
| `ChatRagLiveIntegrationTest` | 1 | 실제 로컬 `gemma4:e2b-it-q4_K_M`, 가상 공개 문서 검색 → 실제 모델 → 완전한 문단/근거 ID 검증 → Fake 답변 저장, 성공 (최종 실행 2.767초) |

기존 `OllamaLiveIntegrationTest` 3개도 실제 로컬 모델로 통과했다. 기존 요약은 3.422초, 공식자료 형식의 가상 문서에 근거한 답변은 3.021초, 근거가 부족한 질문의 FALLBACK은 2.133초였다. 기존 PostgreSQL 요약·채팅 저장·알림·전체 애플리케이션 기동 회귀 테스트도 실행했다. 학교 GPU 서버는 사용하지 않았다.

실모델 테스트의 문서·검토 승인·인증·개인정보 최소화·보존정책·답변 저장은 테스트 Fixture 또는 Fake다. 새 공식자료 저장·조회 테스트만 실제 PostgreSQL I/O이며, 실제 공식자료의 운영 적재나 인증 외부 API 수집을 검증한 것은 아니다. 국민안전24 실원문을 사용한 모델 검증도 수행하지 않았다. 최종 결과 XML/HTML은 Gradle의 `backend/build/test-results/test` 및 `backend/build/reports/tests/test`에 있다.

브랜치는 `feature/ai-dashboard-noti`를 유지했다. Git Commit·Push·Merge·Rebase·Cherry-pick·브랜치 전환, FCM 구현, 타 담당자의 API 수집 구현, 프론트엔드·공통 설정·Gradle·Migration 수정은 수행하지 않았다. 시작 시 존재하던 요구사항 문서의 삭제/추가 작업 상태를 보존했다.
