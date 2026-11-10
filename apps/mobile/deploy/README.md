# OpenStack 배포 안내

이 폴더는 **웹 버전 + 백엔드**를 OpenStack 가상 서버(VM) 한 대에서 Docker 로 운영하기 위한 설정입니다.
모바일 앱(iOS·Android)은 서버에 올리지 않고 스토어로 배포하며, 서버 주소만 이 서버를 가리키게 합니다.

## 전체 구조

```
                         OpenStack VM (Ubuntu + Docker)
사용자 브라우저 ──┐       ┌────────────────────────────────────────────┐
                 ├─ 80 ─▶│ web (nginx)                                  │
모바일 앱 ───────┘       │  ├ /            → 웹 화면 파일 (index.html·JS) │
                         │  └ /api/...     → backend:8080 으로 전달 ──┐  │
                         │ backend (별도 저장소의 이미지) ◀───────────┘  │
                         └────────────────────────────────────────────┘
```

- 바깥으로 열리는 문은 **web 의 80 포트 하나**뿐입니다. 백엔드는 web 을 거쳐서만 접근합니다.
- 웹 화면과 API 가 같은 주소를 쓰므로 CORS(다른 주소 간 요청 허용) 설정이 필요 없습니다.

| 파일 | 하는 일 |
|---|---|
| `../Dockerfile` | 웹 버전을 빌드(`npx expo export -p web`)하고 nginx 이미지에 담음 |
| `../.dockerignore` | 빌드에 보내지 않을 파일 (`node_modules`, 로컬 키 `.env.local` 등) |
| `nginx.conf` | 화면 파일 제공, `/api/` 를 백엔드로 전달, 캐시 설정 |
| `security-headers.conf` | 모든 응답에 붙는 보안 헤더 (HTTPS 를 켜면 맨 아래 HSTS 줄의 `#` 을 지우기) |
| `compose.yaml` | web·backend 컨테이너를 한 번에 실행하는 설정 |
| `.env.example` | 서버에서 채울 값의 예시 (→ `.env` 로 복사) |
| `cloud-init.yaml` | VM 을 처음 만들 때 Docker 를 자동 설치하는 스크립트 |

## 1. OpenStack 에서 서버 만들기 (Horizon 대시보드 기준)

1. **키 페어**: `프로젝트 > 컴퓨트 > 키 페어` 에서 생성 → 내려받은 `.pem` 파일을 잘 보관 (SSH 접속 열쇠)
2. **보안 그룹**: `네트워크 > 보안 그룹` 에서 새 그룹을 만들고 규칙 추가
   | 방향 | 포트 | 원격(허용 대상) | 용도 |
   |---|---|---|---|
   | 수신 | 22 (SSH) | **내 IP/32** 만 | 서버 접속. 전체 공개(0.0.0.0/0) 하지 않기 |
   | 수신 | 80 (HTTP) | 0.0.0.0/0 | 웹·API |
   | 수신 | 443 (HTTPS) | 0.0.0.0/0 | 나중에 HTTPS 를 켤 때 |
3. **인스턴스 시작**: `컴퓨트 > 인스턴스 > 인스턴스 시작`
   - 이미지: Ubuntu 22.04 또는 24.04
   - Flavor: **vCPU 2개 / 메모리 4GB 이상** 권장 (웹 빌드할 때 메모리를 많이 씀. 2GB 면 빌드가 멈출 수 있음)
   - 키 페어·보안 그룹: 위에서 만든 것 선택
   - 구성(Configuration) > 사용자 정의 스크립트: `cloud-init.yaml` 내용을 그대로 붙여 넣기 → 부팅하면서 Docker 자동 설치
4. **유동 IP(Floating IP)**: 인스턴스 오른쪽 메뉴 `유동 IP 연결` → 외부에서 접속할 공인 주소가 생김 (이하 `<서버IP>`)

## 2. 서버에 접속해 실행하기

```bash
# 내 컴퓨터에서
chmod 400 내키.pem
ssh -i 내키.pem ubuntu@<서버IP>

# 서버에서 — Docker 설치가 끝났는지 확인 (cloud-init 은 부팅 후 몇 분 걸림)
docker --version && docker compose version

# 코드 받기 (비공개 저장소면 GitHub 토큰이나 Deploy key 필요)
git clone <저장소 주소> eodiisseo
cd eodiisseo/deploy

# 설정값 작성
cp .env.example .env
nano .env        # TMAP 키 등 입력 후 Ctrl+O, Enter, Ctrl+X

# 빌드 + 실행 (처음엔 몇 분 걸림)
docker compose up -d --build
```

브라우저에서 `http://<서버IP>` 로 접속하면 로그인 화면이 보입니다.

## 3. 자주 쓰는 명령

| 하고 싶은 것 | 명령 (`deploy` 폴더에서) |
|---|---|
| 상태 보기 | `docker compose ps` |
| 로그 보기 | `docker compose logs -f web` (백엔드는 `backend`) |
| 새 버전 배포 | `git pull && docker compose up -d --build` |
| 멈추기 | `docker compose down` |
| 오래된 이미지 정리 | `docker image prune -f` |

`.env` 의 `EXPO_PUBLIC_*` 값을 바꿨다면 **반드시 `--build`** 로 다시 빌드해야 반영됩니다. 이 값들은 빌드할 때 화면 코드에 박히기 때문입니다.

## 4. 백엔드 붙이기

백엔드는 별도 저장소라 여기서는 **완성된 이미지를 받아 실행**만 합니다.

1. 백엔드 저장소에서 Docker 이미지를 만들어 레지스트리(GitHub Container Registry, Docker Hub, OpenStack 의 사설 레지스트리 등)에 올립니다.
2. 서버의 `deploy/.env` 에서 두 줄의 `#` 을 지우고 이미지 이름을 넣습니다.
   ```
   COMPOSE_PROFILES=backend
   BACKEND_IMAGE=ghcr.io/팀이름/eodiisseo-backend:1.0.0
   ```
3. 백엔드 비밀값(DB 비밀번호, OAuth Client Secret 등)은 `deploy/backend.env` 파일에 `이름=값` 형식으로 작성합니다. (git 에 올라가지 않음)
4. `docker compose up -d --build`

백엔드가 맞춰야 할 것:
- 컨테이너 안에서 **8080 포트**로 실행
- API 경로가 `/api/...` 로 시작 (nginx 가 경로를 바꾸지 않고 그대로 전달)

데이터베이스는 이 설정에 없습니다. 백엔드가 DB 를 쓰면 OpenStack 의 DB 서비스(Trove)를 쓰거나, `compose.yaml` 에 DB 컨테이너와 데이터 보관용 볼륨을 추가해야 합니다.

## 5. 도메인·HTTPS (도메인이 생기면 꼭 해야 하는 것)

지금은 `http://<서버IP>` 로 동작합니다. 시험 운영에는 충분하지만, 아래 기능은 **도메인 + HTTPS 없이는 동작하지 않습니다.**

| 기능 | 이유 |
|---|---|
| iOS·Android 앱의 서버 접속 | iOS 는 https 가 아닌 주소를 막고, Android 도 기본으로 막음 |
| 웹의 '현재 위치(GPS)' | 브라우저는 https 페이지에서만 위치 권한을 줌 (http 에서는 위치 직접 지정만 가능) |
| 웹 Google 로그인 | Google 은 IP 주소를 로그인 리다이렉트 주소로 등록할 수 없음 |

도메인이 생기면 할 일:
1. 도메인 DNS 에 **A 레코드** 추가: `도메인 → <서버IP>`
2. HTTPS 인증서 발급(Let's Encrypt, 무료)과 nginx 443 설정 — 도메인이 정해지면 이 저장소에 설정을 추가
3. Google Cloud Console·카카오 디벨로퍼스에 `https://도메인` 을 리다이렉트 주소로 등록
4. TMAP 앱 키에 도메인 제한을 걸었다면 새 도메인 추가

## 6. 모바일 앱이 이 서버를 쓰게 하기

모바일 앱은 상대 주소(`/api`)를 쓸 수 없어 **전체 주소**가 필요합니다. EAS 클라우드 빌드의 환경변수로 넣습니다.

```bash
npx eas-cli@latest env:create --environment production --name EXPO_PUBLIC_API_URL --value https://도메인 --visibility plaintext
```

위 5번의 이유로, 스토어에 낼 앱은 `https://도메인` 이 준비된 뒤에 빌드하세요.

## 문제 해결

| 증상 | 확인할 것 |
|---|---|
| 접속이 안 됨 | 보안 그룹에 80 포트가 열렸는지, 유동 IP 가 연결됐는지, `docker compose ps` 에서 web 이 `running` 인지 |
| 빌드 중 멈추거나 `Killed` | 메모리 부족 → Flavor 를 4GB 이상으로 키우기 |
| 화면은 뜨는데 로그인·API 가 `502` | 백엔드가 없거나 꺼짐 → `docker compose logs backend` |
| 새로고침하면 404 | `nginx.conf` 의 `try_files … /index.html` 이 빠졌는지 확인 |
| `.env` 값을 바꿨는데 그대로 | `--build` 를 붙여 다시 빌드했는지 |
