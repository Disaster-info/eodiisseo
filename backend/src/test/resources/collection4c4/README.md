# 4C-4 isolated verification

No production listener, API approval, scheduler or default Bean is enabled.

The Gateway harness uses a NEW TEMP PostgreSQL data directory on **127.0.0.2:55433**.
It never reuses the 127.0.0.1:55433 audit cluster. The SQL name/port guards and
CollectionOperationMode.DISPOSABLE_AUDIT URL guard remain unchanged; the TLS test
explicitly checks that the latter still rejects this separate endpoint.
The harness checks the actual server data_directory, host, port and pg_stat_ssl.

Prerequisites: already installed PostgreSQL 17 with PostGIS, Git OpenSSL, JDK21;
approved disposable database tests. No automatic installation/download.

From the repository in PowerShell (TEMP roots must be new):

```powershell
$pg = "$env:LOCALAPPDATA\eodiisseo\postgresql17\pgsql\bin"
$openssl = 'C:\Program Files\Git\usr\bin\openssl.exe'
$gatewayRoot = Join-Path $env:TEMP ('c4c4-gateway-tls-' + [guid]::NewGuid().ToString('N'))
$tlsRoot = Join-Path $env:TEMP ('c4c3-tls-' + [guid]::NewGuid().ToString('N'))
try {
  & ./backend/src/test/resources/collection4c4/Prepare-GatewayTlsAudit.ps1 -Root $gatewayRoot -PostgresBin $pg -OpenSsl $openssl
  & ./backend/src/test/resources/collection4c4/Prepare-CommandPeerCertificates.ps1 -Root $gatewayRoot -OpenSsl $openssl
  & ./backend/src/test/resources/collection4c3/Prepare-TlsAudit.ps1 -Root $tlsRoot -PostgresBin $pg -OpenSsl $openssl
  & ./backend/src/test/resources/collection4c4/Protect-OwnedTlsFixture.ps1 -Root $tlsRoot
  $env:EODIISSEO_LOCAL_DB_TESTS = 'true'
  $env:EODIISSEO_AUDIT_DISPOSABLE = 'true'
  $env:EODIISSEO_AUDIT_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55433/postgres'
  $env:EODIISSEO_GATEWAY_TLS_TESTS = 'true'
  $env:EODIISSEO_GATEWAY_TLS_ROOT = $gatewayRoot
  $env:EODIISSEO_TLS_TESTS = 'true'
  $env:EODIISSEO_TLS_TEST_ROOT = $tlsRoot
  $env:SPRING_FLYWAY_ENABLED = 'true'
  $env:AUTH_BRIDGES_ENABLED = 'true'
  ./backend/gradlew.bat -p backend clean build "-PbuildOutputDir=$env:TEMP\eodiisseo-stage4c4-build" --project-cache-dir "$env:TEMP\eodiisseo-stage4c4-cache" --no-daemon --console=plain
  if ($LASTEXITCODE) { throw 'Build failed; preserve reports before retrying' }
} finally {
  & ./backend/src/test/resources/collection4c4/Stop-GatewayTlsAudit.ps1 -Root $gatewayRoot -PostgresBin $pg
  & ./backend/src/test/resources/collection4c3/Stop-TlsAudit.ps1 -Root $tlsRoot -PostgresBin $pg
}
```

Do not print/copy bootstrap.password, private keys or PKCS12 files. These are
throwaway test credentials, not a production secret store or OS principal isolation.
The stop scripts validate TEMP prefix, exact directory, executable/PID and endpoint
before removing the owned directories. If cleanup refuses, investigate; never broaden
its target. Test assertions and disabled external-model/API tests remain unchanged.

Real SQL tests: CollectionCommandFleetPostgresTest, CollectionGatewayTlsDomainPostgresTest.
The mutual-TLS command test additionally requires EODIISSEO_GATEWAY_TLS_TESTS and its
owned certificate fixtures. Codec tests use no network. JVM restart testing uses
bounded stdin with synthetic credentials; it does not establish kernel/ACL isolation.

Persisted Fleet fencing serializes DB permission revocation with COMMIT. A write
ordered before revocation may commit; subsequent generations are rejected. External
supervisor-to-DB atomicity, authoritative peer listener/process deployment and operating
API evidence remain blockers. No readiness flag removes these requirements.

The Gateway fixture root is ACL-protected for the current SID and SYSTEM before credentials are created. The older TLS fixture is explicitly hardened after preparation. This does not isolate processes sharing the same SID.
