param(
    [Parameter(Mandatory=$true)][guid]$MemberId
)
$ErrorActionPreference = 'Stop'
if ($MemberId -eq [guid]::Empty) { throw 'Specify the designated development member.' }
$bearer = $env:EODIISSEO_OPS_IMPORT_BEARER
if ([string]::IsNullOrWhiteSpace($bearer)) { throw 'A genuine session for the designated development member is required.' }

Add-Type -AssemblyName System.Net.Http
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.BaseAddress = [uri]'http://127.0.0.1:8081/'
$client.Timeout = [TimeSpan]::FromSeconds(10)
function Get-LocalStatus([string]$Path) {
    $reply = $client.GetAsync($Path).GetAwaiter().GetResult()
    try { [int]$reply.StatusCode } finally { $reply.Dispose() }
}
try {
    if ((Get-LocalStatus 'api/v1/ops/status') -ne 401) { throw 'Expected anonymous OPS status 401.' }
    $client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$bearer)
    $me = $client.GetAsync('api/v1/members/me').GetAwaiter().GetResult()
    try {
        if ([int]$me.StatusCode -ne 200) { throw 'The development session was rejected.' }
        $identity = $me.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        if ($identity.memberId -ne $MemberId.ToString()) { throw 'The session belongs to a different member.' }
    } finally { $me.Dispose() }
    if ((Get-LocalStatus 'api/v1/ops/status') -ne 403) { throw 'Expected the designated ordinary session to receive 403.' }

    $repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
    $config = @{}
    Get-Content -LiteralPath (Join-Path $repo '.env') | ForEach-Object {
        if ($_ -match '^(DB_URL|DB_USERNAME|DB_PASSWORD)=(.*)$') {
            $config[$Matches[1]] = $Matches[2].Trim().Trim('"').Trim("'")
        }
    }
    if ($config.DB_URL -ne 'jdbc:postgresql://127.0.0.1:55432/eodiisseo_local' -or
        $config.DB_USERNAME -ne 'eodiisseo_app' -or !$config.DB_PASSWORD) {
        throw 'Unexpected local development DB configuration.'
    }
    $auditRoot = Join-Path $env:LOCALAPPDATA 'eodiisseo/db-backups'
    New-Item -ItemType Directory -Force -Path $auditRoot | Out-Null
    $auditPath = Join-Path $auditRoot ('ops-bootstrap-' + [guid]::NewGuid().ToString('N') + '.json')
    $audit = [ordered]@{
        target='127.0.0.1:55432/eodiisseo_local'; memberId=$MemberId.ToString(); role='OPS'
        authorization='Explicit development-only approval, one member'
        timestampUtc=[DateTime]::UtcNow.ToString('o'); anonymousStatus=401; ordinaryStatus=403; state='PENDING'
    }
    $encoding = [Text.UTF8Encoding]::new($false)
    [IO.File]::WriteAllText($auditPath,($audit | ConvertTo-Json),$encoding)
    $psql = Join-Path $env:LOCALAPPDATA 'eodiisseo/postgresql17/pgsql/bin/psql.exe'
    $oldPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $config.DB_PASSWORD
        $sql = @"
BEGIN;
LOCK TABLE member_role IN SHARE ROW EXCLUSIVE MODE;
DO `$`$
BEGIN
  IF current_database() <> 'eodiisseo_local' OR inet_server_port() <> 55432
     OR current_user <> 'eodiisseo_app' THEN RAISE EXCEPTION 'Unexpected development DB'; END IF;
  IF EXISTS (SELECT 1 FROM member_role WHERE role='OPS') THEN RAISE EXCEPTION 'An OPS role already exists'; END IF;
  IF NOT EXISTS (
     SELECT 1 FROM member m JOIN member_role r USING(member_id)
     WHERE m.member_id='$MemberId'::uuid AND m.status='ACTIVE' AND r.role='USER'
  ) THEN RAISE EXCEPTION 'Designated active USER member required'; END IF;
  INSERT INTO member_role(member_id,role,granted_at) VALUES ('$MemberId'::uuid,'OPS',clock_timestamp());
END `$`$;
COMMIT;
"@
        $sql | & $psql -X -w -h 127.0.0.1 -p 55432 -U eodiisseo_app -d eodiisseo_local -q -v ON_ERROR_STOP=1
        if ($LASTEXITCODE -ne 0) { throw 'The one-member OPS bootstrap failed. Review the local audit record.' }
        $audit.state='COMMITTED'
        [IO.File]::WriteAllText($auditPath,($audit | ConvertTo-Json),$encoding)
    } finally { $env:PGPASSWORD = $oldPassword }
    $audit.opsStatus=Get-LocalStatus 'api/v1/ops/status'
    [IO.File]::WriteAllText($auditPath,($audit | ConvertTo-Json),$encoding)
    if ($audit.opsStatus -ne 200) { throw "Role committed. OPS HTTP verification failed. Review $auditPath before retrying." }
    [pscustomobject]@{role='OPS'; status=200; auditPath=$auditPath}
} finally {
    $client.Dispose()
}
