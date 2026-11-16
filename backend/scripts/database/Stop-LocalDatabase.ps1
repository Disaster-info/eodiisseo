param([switch]$Audit,[string]$InstallRoot=(Join-Path $env:LOCALAPPDATA 'eodiisseo/postgresql17'))
$data=Join-Path $InstallRoot $(if($Audit){'audit-data'}else{'data'})
& (Join-Path $InstallRoot 'pgsql/bin/pg_ctl.exe') -D $data -m fast -w stop
if($LASTEXITCODE -ne 0){throw 'Database stop failed'}
