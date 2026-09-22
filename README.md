# 어디있어 🧭

> 지진·호우·산불 재난정보를 한곳에 모으고, 내 위치와 관심지역 기준으로 대피소·안심 그룹·체크리스트를 제공하는 재난 대응 앱

캡스톤 디자인 프로젝트 · 팀 **살려주세요**

## 주요 기능

| 기능 | 설명 | 단계 |
|---|---|---|
| 재난정보 대시보드 | 지진·호우·산불 정보를 공통 형식으로 통합하고 현재 위치·관심지역 관련 재난을 우선 표시 | 1차 |
| 날씨 | 현재 기온·기상상태와 규칙 기반 안내문 | 1차 |
| 주변 대피소 | 기준 위치 1km 반경부터 검색, 없으면 1km씩 확대 | 1차 |
| 재난 대비 체크리스트 | 공식 행동요령 기반 유형별 체크리스트와 완료율 | 1차 |
| AI 재난정보 요약 | 공식 원문을 유지한 채 핵심 내용 요약 | 1차 |
| 안심 그룹 | 가족·친구 그룹원의 위치와 재난 영향 여부 확인 | 2차 |
| 스마트 알림 | 관심지역·심각도 기준 FCM Push, 중복 방지 | 2차 |
| 대피경로 | 대피소까지 도보 경로 안내 | 2차 |

## 기술 스택

| 구분 | 기술 |
|---|---|
| Mobile | React Native |
| Backend | Spring Boot 3, Spring Security, JPA |
| Database | PostgreSQL + PostGIS, Redis |
| 지도·경로 | 카카오맵 SDK, 카카오 로컬 API, TMAP 보행자 경로 API |
| 외부 데이터 | 행정안전부 재난안전데이터공유플랫폼, 기상청 API |
| 알림 | Firebase Cloud Messaging |
| AI | LLM API (재난정보 요약) |

## 디렉터리 구조

```
.
├── apps/mobile/        # React Native 앱
├── backend/            # Spring Boot API 서버 + 수집 모듈
├── mock-data/          # 시연용 테스트 재난 데이터 (FR-TEST)
│   └── scenarios/      # 발생→격상→해제 등 시간순 시나리오
├── docs/
│   ├── requirements/   # 기획서, 요구사항 분석서, 기능 명세
│   ├── design/         # ERD, 화면 설계
│   └── api/            # REST API 명세, 외부 API 조사
└── scripts/            # 저장소 초기 설정 스크립트
```

## 시작하기

### 1. 환경 변수

```bash
cp .env.example .env   # 발급받은 키를 채워 넣습니다. .env는 절대 커밋하지 않습니다.
```

### 2. Backend

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=local'
# 시연용 Mock API 사용 시
./gradlew bootRun --args='--spring.profiles.active=mock'
```

### 3. Mobile

```bash
cd apps/mobile
npm install
npm run android   # 또는 npm run ios
```

## 협업 규칙

브랜치 전략, 커밋 메시지, PR 규칙은 [CONTRIBUTING.md](./CONTRIBUTING.md)를 참고하세요.

## 팀원

| 이름 | 역할 | GitHub |
|---|---|---|
| 이경민 | 팀장 · Frontend(Mobile), 화면 구성, 일정·협업 관리 | [@TODO](https://github.com/) |
| 김무겸 | Backend, 서버·배포 환경 | [@TODO](https://github.com/) |
| 신경진 | Backend, DB 설계, 문서 | [@TODO](https://github.com/) |
| 허동우 | Backend, 지도·GIS | [@TODO](https://github.com/) |
