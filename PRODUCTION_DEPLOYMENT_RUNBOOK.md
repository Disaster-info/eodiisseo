# 어디있어 운영 배포 실행 가이드

## 승인과 중단 조건

OpenStack 접속과 운영 변경 권한을 먼저 확인한다. DB 쓰기, 컨테이너 재생성, 수집 활성화, 실제 FCM은 각각 적용 대상을 알려 승인받은 뒤 실행한다. 처음 점검한 컨테이너의 이미지 ID, 실제 서버 소스 SHA, Flyway 이력, 백업 복원 결과 중 하나라도 확인하지 못하면 배포 단계에서 멈춘다.

## 읽기 전용 사전 점검

운영 서버의 Bash에서 실제 실행 프로젝트와 다섯 Compose 파일을 사용한다.

```bash
set -euo pipefail
repo=/home/ubuntu/eodiisseo
cd "$repo/deploy"
dc=(docker compose -p eodiisseo-runtime
  -f compose.yaml -f compose.override.yaml -f compose.web.yaml
  -f compose.mtu.yaml -f compose.gpu.yaml)
git -C "$repo" status --short
git -C "$repo" rev-parse HEAD
"${dc[@]}" config --quiet
"${dc[@]}" config --services
"${dc[@]}" ps
docker inspect --format '{{.Name}} {{.Image}} {{.HostConfig.NetworkMode}}' \
  eodiisseo-runtime-backend-1 eodiisseo-runtime-gpu-tunnel-1
```

병합된 Compose 전체, JVM 인자 전부나 컨테이너 환경 전체를 출력하지 않는다. 실행 컨테이너의 불리언 설정만 허용 목록으로 확인한다. Spring 명령행, JVM 시스템 속성, JSON 설정, 환경변수와 설정 파일에 중복 선언이 있는지 운영 서버에서 값 공개 없이 비교하고, 빈 선언이나 별칭을 실제 활성으로 간주하지 않는다. 기존 Flyway 위치, 운영 전용 Dockerfile, GPU 네임스페이스, MTU 구성을 신구 파일 간 비교한다.

```bash
flags=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' eodiisseo-runtime-backend-1)
printf '%s\n' "$flags" |
  grep -Ei '^(AUTH_BRIDGES_ENABLED|COLLECTION_WORKER_ENABLED|EODIISSEO_(OWNED_HTTP|OWNED_RUNTIME|AI_OLLAMA|FCM|NOTIFICATION_(PERSISTENCE|AUTH|CRYPTO)|RUNTIME)_ENABLED|eodiisseo\.(owned-http|owned-runtime|runtime|notification\.(persistence|auth|crypto))\.enabled)=(true|false)$' ||
  { rc=$?; if (( rc != 1 )); then exit "$rc"; fi; }
unset flags
dbpsql=( "${dc[@]}" exec -T db sh -c 'exec psql -X -w -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "${POSTGRES_DB:-$POSTGRES_USER}" "$@"' sh )
"${dbpsql[@]}" -c 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank'
"${dbpsql[@]}" < "$repo/backend/scripts/database/production-readiness.sql"
```

이 SQL은 운영자 셸에서만 실행한다. 승인 기록 수는 만료·철회·주입 검토 조건을 만족하는 행의 수다. 애플리케이션 검색 시점의 원본 fingerprint 확인과 실제 검색 테스트가 추가로 필요하다. Ollama의 `/api/version`과 `/api/tags`는 실제 백엔드와 같은 네임스페이스에서 이미 설치된 클라이언트로 조회하고, 설정 모델의 정확한 태그를 대조한다. 셸에 HTTP 도구가 없다면 읽기 전용 점검을 위해 운영 이미지에 패키지를 설치하지 않는다.

## 백업과 복원 리허설

```bash
umask 077
backup=$(mktemp -d "$HOME/eodiisseo-predeploy.XXXXXXXX")
printf '%s\n' "$backup"
cp -a compose.yaml compose.override.yaml compose.web.yaml compose.mtu.yaml compose.gpu.yaml "$backup/"
if test -f .env; then cp -a .env "$backup/compose.env"; fi
git -C "$repo" rev-parse HEAD > "$backup/source-sha"
docker inspect --format '{{.Image}}' eodiisseo-runtime-backend-1 > "$backup/backend-image-id"
"${dc[@]}" exec -T db sh -c +  'exec pg_dump -w -Fc -U "$POSTGRES_USER" -d "${POSTGRES_DB:-$POSTGRES_USER}"' > "$backup/db.dump"
test -s "$backup/db.dump"
sha256sum "$backup/db.dump" > "$backup/db.dump.sha256"
"${dc[@]}" exec -T db pg_restore --list < "$backup/db.dump" > "$backup/db.dump.list"
```

비밀 마운트 경로를 운영자만 확인하고, 실제 참조하는 키·환경 파일을 동일한 접근 제한의 암호화 백업에 보관한다. DB 덤프의 SHA-256을 검증한 뒤 운영 서버와 분리된 PostgreSQL 17/PostGIS 리허설 서버에서만 아래를 실행한다. `RESTORE_HOST`는 사전에 승인된 폐기용 DB 호스트이며, 운영 주소와의 대조 후 실행한다.

```bash
: "${RESTORE_HOST:?set disposable restore host}"
rehearsal="eodiisseo_restore_$(date +%s)"
createdb -h "$RESTORE_HOST" "$rehearsal"
pg_restore --exit-on-error --no-owner -h "$RESTORE_HOST" -d "$rehearsal" "$backup/db.dump"
psql -X -v ON_ERROR_STOP=1 -h "$RESTORE_HOST" -d "$rehearsal" \
  -f "$repo/backend/scripts/database/production-readiness.sql"
```

리허설에서 테이블·Flyway 이력과 핵심 건수를 확인하고 새 소스로 Flyway 검증과 앱 기동을 재현한다. 복구에 필요한 역할·확장·경로가 누락되면 이미지 교체를 중단한다.

## 승인 후 이미지 교체

승인된 소스 SHA가 체크아웃되어 있는지 확인한다. 배포 파일 및 빌드 컨텍스트의 미추적 내용은 기존 운영용 파일과 검토한 뒤 보존한다. `config --services`에서 `backend`와 `gpu-tunnel` 이름을 확인한다. 두 서비스가 공유 네임스페이스를 사용하므로 교체·롤백 시 항상 같이 재생성한다. 작업 승인은 두 컨테이너의 일시 중단을 포함해야 한다.

```bash
: "${APPROVED_SHA:?set the reviewed source commit}"
test "$(git -C "$repo" rev-parse HEAD)" = "$APPROVED_SHA"
test -z "$(git -C "$repo" status --porcelain --untracked-files=no -- backend)"
old_image=$(cat "$backup/backend-image-id")
rollback_tag="eodiisseo-backend:rollback-$(date +%s)"
docker tag "$old_image" "$rollback_tag"
printf 'services:\n  backend:\n    image: %s\n' "$rollback_tag" > "$backup/backend-rollback.yaml"
printf 'services:\n  backend:\n    build:\n      labels:\n        org.opencontainers.image.revision: "%s"\n' \
  "$APPROVED_SHA" > "$backup/backend-build-label.yaml"
"${dc[@]}" -f "$backup/backend-build-label.yaml" config --quiet
"${dc[@]}" -f "$backup/backend-build-label.yaml" build backend
"${dc[@]}" up -d --no-deps --no-build --force-recreate backend gpu-tunnel
"${dc[@]}" ps backend gpu-tunnel
backend_pid=$(docker inspect --format '{{.State.Pid}}' eodiisseo-runtime-backend-1)
tunnel_pid=$(docker inspect --format '{{.State.Pid}}' eodiisseo-runtime-gpu-tunnel-1)
test "$backend_pid" -gt 0 && test "$tunnel_pid" -gt 0
test "$(stat -Lc '%i' "/proc/$backend_pid/ns/net")" = "$(stat -Lc '%i' "/proc/$tunnel_pid/ns/net")"
docker inspect --format '{{.Image}}' eodiisseo-runtime-backend-1 > "$backup/new-backend-image-id"
test "$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' \
  "$(cat "$backup/new-backend-image-id")")" = "$APPROVED_SHA"
printf '%s\n' "$APPROVED_SHA" > "$backup/new-source-sha"
```

빌드 기록의 SHA와 이미지 ID를 함께 보관한다. 기존 실행 컨테이너의 네트워크 모드가 유지되고 새 백엔드가 시작되었는지 확인한다. Flyway 이력, API의 인증 거부·승인 계정 조회, DB 카운트와 GPU 루프백 연결을 다시 확인한다. 이전 이미지와 DB 스키마가 호환될 때만 아래 롤백을 실행한다. DB 스키마가 비호환이면 서비스 쓰기를 중단하고 복원·데이터 재조정 범위의 별도 승인을 받는다.

```bash
"${dc[@]}" -f "$backup/backend-rollback.yaml" up -d --no-deps --no-build --force-recreate backend gpu-tunnel
"${dc[@]}" ps backend gpu-tunnel
```

## 데이터 적재 및 기능 활성화

1. **행정구역**. 원본 기관·이용권한·기준연도, 계층 코드, PRJ 및 EPSG:4326 변환, Polygon 유효성, 폐지 코드, TMAP 대응표를 검토한다. 승인한 `{ "areas": [...] }` 파일의 SHA-256을 고정한다. 준비가 안 된 좌표계나 매핑으로 적재하지 않는다. Windows 운영자 셸에서 먼저 `-Apply` 없이 검사하고 승인 후 적용한다.

   ```powershell
   .\backend\scripts\database\Import-VerifiedAdministrativeAreas.ps1 -InputFile .\approved-areas.json -ExpectedSha256 $approvedHash
   .\backend\scripts\database\Import-VerifiedAdministrativeAreas.ps1 -InputFile .\approved-areas.json -ExpectedSha256 $approvedHash -BaseUri $approvedApiOrigin -Apply
   ```

2. **대피소**. 현 30건을 보존한다. 전국 적재는 담당자가 공식 제공처 승인, 전체 페이지 개수와 식별자, 좌표, 불완전 응답의 비활성화 방지, 멱등 재실행을 검증한 일괄 작업으로 수행한다. 승인이 확인되기 전에는 실행 명령을 확정하지 않는다.
3. **재난 수집**. 데이터 제공처별 한 실행 주체와 승인된 계약을 지정한다. `COLLECTION_WORKER_ENABLED` 하나로 일괄 활성화하지 않는다. 승인된 실행 이후 최신 LIVE Run, 원문, Revision, Coverage, Outbox의 관측 결과를 대조한다.
4. **알림 설정**. 기존 회원 보정은 아래 조회로 대상 건수를 확인한 후 별도 승인받아 적용한다. 두 번째 적용에서 0건을 확인한다. 알림 설정의 저장소와 인증 설정을 먼저 확인하고 FCM과 기기 암호화의 활성화는 따로 결정한다.

   ```bash
   "${dbpsql[@]}" < "$repo/backend/scripts/database/initialize-notification-settings.sql"
   "${dbpsql[@]}" -v apply=true < "$repo/backend/scripts/database/initialize-notification-settings.sql"
   "${dbpsql[@]}" < "$repo/backend/scripts/database/production-readiness.sql"
   ```

5. **RAG·AI**. 권한이 확인된 원문만 기존 OPS 승인 절차로 적재한다. 승인 fingerprint와 유효기간, 임베딩 모델·차원 및 pgvector 사용 여부를 검증한다. 승인된 합성 질문과 테스트 계정으로 `POST /api/v1/ai/guidance`를 호출한다. 판정은 `answerType=AI`, `COMPLETED`, 모델명, 자료 연결, Ollama 호출 증거를 함께 본다. FALLBACK은 별도 결과로 남긴다.
6. **Smoke test**. 외부 브라우저에서 실제 인증, 재난·대피소·관심지역, 날씨, 안심그룹, 체크리스트, 소셜 연결과 AI를 테스트 계정으로 확인한다. FCM은 승인된 기기로만 확인한다. 499와 DNS는 요청 시각·상태·Trace ID 및 타이밍으로 비교하고 헤더, 토큰, 상세 위치를 수집하지 않는다.
