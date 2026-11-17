param([switch]$Audit, [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'eodiisseo/postgresql17'))
$ErrorActionPreference='Stop'
$bin=Join-Path $InstallRoot 'pgsql/bin'
$port=if($Audit){55433}else{55432}
$data=Join-Path $InstallRoot $(if($Audit){'audit-data'}else{'data'})
$adminPassword=Join-Path $InstallRoot 'admin-password.txt'
function Invoke-Pg([string]$Program,[string[]]$Parameters) {
    & (Join-Path $bin $Program) @Parameters
    if($LASTEXITCODE -ne 0){throw "$Program failed ($LASTEXITCODE)"}
}
if(!(Test-Path -LiteralPath $adminPassword)){
    $bytes=New-Object byte[] 48;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [IO.File]::WriteAllText($adminPassword,[Convert]::ToBase64String($bytes))
    $ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls.exe $adminPassword /inheritance:r /grant:r "*$($ownerSid):(F)" | Out-Null
}
if(!(Test-Path -LiteralPath (Join-Path $data 'PG_VERSION'))){
    if(Test-Path -LiteralPath $data){throw "Existing incomplete data directory: $data"}
    Invoke-Pg 'initdb.exe' @('-D',$data,'-U','postgres','--encoding=UTF8','--locale=C','--auth=scram-sha-256',"--pwfile=$adminPassword")
    [IO.File]::AppendAllText((Join-Path $data 'postgresql.conf'),"`nlisten_addresses = '127.0.0.1'`nport = $port`ntimezone = 'UTC'`n")
    if($Audit){
        $hba=Join-Path $data 'pg_hba.conf'
        $text=[IO.File]::ReadAllText($hba)
        # Only the isolated loopback audit cluster permits the fixture account without a password.
        [IO.File]::WriteAllText($hba,"host all eodiisseo_audit 127.0.0.1/32 trust`n"+$text)
    }
}
& (Join-Path $bin 'pg_ctl.exe') -D $data status *> $null
if($LASTEXITCODE -ne 0){
    $log=Join-Path $InstallRoot $(if($Audit){'audit-postgresql.log'}else{'postgresql.log'})
    $process=Start-Process -FilePath (Join-Path $bin 'pg_ctl.exe') -ArgumentList @('-D',('"'+$data+'"'),'-l',('"'+$log+'"'),'-w','start') -WindowStyle Hidden -PassThru
    $process.WaitForExit()
    if($process.ExitCode -ne 0){throw "DB start failed; inspect $log"}
}
$previousPassword=$env:PGPASSWORD
try {
    $env:PGPASSWORD=[IO.File]::ReadAllText($adminPassword)
    $connection=@('-X','-h','127.0.0.1','-p',"$port",'-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1')
    if($Audit){
        $query="DO `$`$ BEGIN IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='eodiisseo_audit') THEN CREATE ROLE eodiisseo_audit LOGIN SUPERUSER; END IF; END `$`$;"
        Invoke-Pg 'psql.exe' ($connection+@('-c',$query))
    } else {
        $repoRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
        $envPath=Join-Path $repoRoot '.env'
        if(!(Test-Path -LiteralPath $envPath)){
            $bytes=New-Object byte[] 48;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes);$appPassword=[Convert]::ToBase64String($bytes)
            [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes);$jwt=[Convert]::ToBase64String($bytes)
            [IO.File]::WriteAllText($envPath,"DB_URL=jdbc:postgresql://127.0.0.1:55432/eodiisseo_local`nDB_USERNAME=eodiisseo_app`nDB_PASSWORD=$appPassword`nJWT_SECRET=$jwt`nAUTH_BRIDGES_ENABLED=true`n")
            $ownerSid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            & icacls.exe $envPath /inheritance:r /grant:r "*$($ownerSid):(F)" | Out-Null
        }
        $settings=@{};Get-Content -LiteralPath $envPath | ForEach-Object {if($_ -match '^([A-Z_]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
        if($settings.DB_USERNAME -ne 'eodiisseo_app' -or $settings.DB_URL -ne 'jdbc:postgresql://127.0.0.1:55432/eodiisseo_local'){throw 'Existing .env targets another database; it was preserved.'}
        $safePassword=$settings.DB_PASSWORD.Replace("'","''")
        $query="SELECT 1 FROM pg_roles WHERE rolname='eodiisseo_app'"
        $exists=& (Join-Path $bin 'psql.exe') @connection -At -c $query
        if(!$exists){Invoke-Pg 'psql.exe' ($connection+@('-c',"CREATE ROLE eodiisseo_app LOGIN PASSWORD '$safePassword'"))}
        $exists=& (Join-Path $bin 'psql.exe') @connection -At -c "SELECT 1 FROM pg_database WHERE datname='eodiisseo_local'"
        if(!$exists){Invoke-Pg 'createdb.exe' @('-h','127.0.0.1','-p',"$port",'-U','postgres','-O','eodiisseo_app','eodiisseo_local')}
        Invoke-Pg 'psql.exe' @('-X','-h','127.0.0.1','-p',"$port",'-U','postgres','-d','eodiisseo_local','-v','ON_ERROR_STOP=1','-c','CREATE EXTENSION IF NOT EXISTS postgis')
    }
} finally {$env:PGPASSWORD=$previousPassword}
Write-Output "Local database listening at 127.0.0.1:$port. Passwords were not printed."
