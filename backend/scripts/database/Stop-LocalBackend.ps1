$ErrorActionPreference='Stop'
$backend=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$pidFile=Join-Path $backend 'build/local/backend.pid'
if(!(Test-Path -LiteralPath $pidFile)){Write-Output 'No local backend PID file.';return}
$backendProcessId=[int][IO.File]::ReadAllText($pidFile)
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$backendProcessId"
if(!$process){Write-Output 'Local backend already stopped.';return}
if($process.Name -notmatch '^java(w)?\.exe$' -or $process.CommandLine -notmatch 'eodiisseo-db-build' -or $process.CommandLine -notmatch 'server.address=127.0.0.1'){
    throw 'PID does not match the local backend; no process was stopped.'
}
Stop-Process -Id $backendProcessId
Write-Output "Stopped local backend PID $backendProcessId. Database is still running."
