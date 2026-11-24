# Windows 로컬 PostgreSQL의 pgvector

기준 조합은 PostgreSQL 17.11, PostGIS 3.6.2, pgvector 0.8.7과 Visual Studio 2026 C++ x64다. 설치 트리 `%LOCALAPPDATA%\eodiisseo\postgresql17\pgsql`를 개발 클러스터와 Audit 클러스터가 함께 사용한다. 확장 활성화는 데이터베이스별로 이루어진다. 개발 DB `127.0.0.1:55432/eodiisseo_local`의 데이터를 테스트 대상으로 사용하지 않는다. [pgvector 공식 Windows 설치 안내](https://github.com/pgvector/pgvector#windows)

## 개발 헤더 확인과 복구

`Install-LocalDatabase.ps1`는 PostgreSQL ZIP에서 `bin`, `lib`, `share`, `include`를 추출한다. 예전 스크립트로 설치한 트리는 `include`가 빠져 있을 수 있다. 기존 설치의 `postgres.h`와 `postgres.lib`를 먼저 확인하고, 헤더가 있으면 복구를 건너뛴다. 이미 정상인 설치를 복구하려고 설치 스크립트를 다시 실행할 필요는 없다.

```powershell
$root = Join-Path $env:LOCALAPPDATA 'eodiisseo/postgresql17'
$pg = Join-Path $root 'pgsql'
& "$pg/bin/pg_config.exe" --version --includedir-server --pkglibdir --sharedir
Test-Path "$pg/include/server/postgres.h"
Test-Path "$pg/lib/postgres.lib"
```

PostgreSQL 17.11 x64의 동일한 검증된 ZIP에서 헤더만 복구한다. 다른 버전의 헤더나 import library를 섞지 않는다. `postgres.lib`가 없으면 같은 패키지의 `pgsql/lib/postgres.lib`를 확인해 개별 복구한다.

```powershell
$archive = Join-Path $root 'postgresql.zip'
$expected = '80379b2c04d51c30225532e0ae04509899141e9957ed096fe749d7fd9df8f82f'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $expected) {
    throw 'PostgreSQL 17.11 archive checksum mismatch'
}
$stage = New-Item -ItemType Directory (Join-Path $env:TEMP ('eodiisseo-pg-headers-' + [guid]::NewGuid()))
tar.exe -xf $archive -C $stage.FullName pgsql/include
if ($LASTEXITCODE -ne 0) { throw 'Could not extract PostgreSQL headers' }
Copy-Item (Join-Path $stage.FullName 'pgsql/include') -Destination $pg -Recurse -Force
if (!(Test-Path "$pg/include/server/postgres.h")) { throw 'PostgreSQL server header missing' }
```

## 이미 설치된 PostgreSQL에 확장 빌드

Visual Studio의 **x64 Native Tools Command Prompt**에서 실행한다. 미리 확보한 동일 태그 소스가 있으면 해당 소스 디렉터리를 재사용한다. 기존 확장 DLL을 교체해야 할 때에는 해당 설치 트리를 사용하는 로컬 클러스터의 사용 상태를 확인한다.

```bat
set "PGROOT=%LOCALAPPDATA%\eodiisseo\postgresql17\pgsql"
cd /d "%TEMP%"
git clone --branch v0.8.7 --depth 1 https://github.com/pgvector/pgvector.git pgvector-0.8.7
cd pgvector-0.8.7
nmake /F Makefile.win
nmake /F Makefile.win install
```

빌드 성공 후 `lib\vector.dll`, `share\extension\vector.control`, `share\extension\vector--0.8.7.sql`을 확인한다. 이미 같은 버전이 정상 로드되는 설치에는 빌드·설치가 필요 없다.

## Audit DB 및 폐기용 DB 검증

저장소 루트에서 기존 Audit 클러스터를 시작한다. 아래 `CREATE EXTENSION`의 대상은 **55433의 Audit postgres**다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File backend/scripts/database/Start-LocalDatabase.ps1 -Audit
$psql = Join-Path $env:LOCALAPPDATA 'eodiisseo/postgresql17/pgsql/bin/psql.exe'
& $psql -X -w -h 127.0.0.1 -p 55433 -U eodiisseo_audit -d postgres -v ON_ERROR_STOP=1 `
  -c 'CREATE EXTENSION IF NOT EXISTS vector;' `
  -c "SELECT extversion FROM pg_extension WHERE extname='vector';"

$env:EODIISSEO_LOCAL_DB_TESTS = 'true'
$env:EODIISSEO_AUDIT_DISPOSABLE = 'true'
$env:EODIISSEO_AUDIT_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55433/postgres'
Remove-Item Env:EODIISSEO_ISOLATED_VECTOR_JDBC_URL,Env:EODIISSEO_ISOLATED_VECTOR_DISPOSABLE -ErrorAction SilentlyContinue
& .\backend\gradlew.bat -p backend test --tests '*RagVectorPipelineIntegrationTest' --tests '*ChatRagHttpAuditTest'
```

`RagVectorPipelineIntegrationTest`는 Audit 연결로 고유한 `owned_...` 테스트 DB를 생성하고 테스트 종료 시 정리한다. `realPgvectorExactSqlWhenExtensionIsAvailable`가 그 DB에서 `CREATE EXTENSION vector`를 실행한다. Audit의 `postgres` DB에 활성화한 확장은 새 테스트 DB로 자동 상속되지 않는다. 별도 폐기용 DB를 수동 검증할 때에도 대상 DB와 포트를 확인한 뒤 그 DB 안에서 한 번 활성화한다.

Gradle JUnit XML에서 실제 SQL 테스트에 `<skipped/>`가 없는지 확인한다. 합성 임베딩과 루프백 HTTP 대역을 사용하는 나머지 회귀는 실제 모델의 품질 또는 운영 연결 검증과 구분한다.
