$ErrorActionPreference='Stop'
$backend=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$pidFile=Join-Path $backend 'build/local/backend.pid'
if(!(Test-Path -LiteralPath $pidFile)){Write-Output 'No local backend PID file.';return}
$backendProcessId=[int][IO.File]::ReadAllText($pidFile)
$process=Get-CimInstance Win32_Process -Filter "ProcessId=$backendProcessId"
if(!$process){Write-Output 'Local backend already stopped.';return}
$metadataPath=Join-Path $backend 'build/local/backend.process.json'
$matchesJar=$false
if(Test-Path -LiteralPath $metadataPath){
    $metadata=Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $sameStart=[Math]::Abs(($process.CreationDate.ToUniversalTime()-[DateTime]::Parse($metadata.startedAtUtc).ToUniversalTime()).TotalSeconds) -lt 2
    $matchesJar=$metadata.pid -eq $backendProcessId -and $sameStart -and $process.CommandLine.Contains([string]$metadata.jar)
}else{
    # Compatibility with PID files made before process metadata was added.
    foreach($build in @('eodiisseo-db-build','eodiisseo-local-test-build','eodiisseo-notification-merge-build')){
        $knownJar=[IO.Path]::GetFullPath((Join-Path $env:TEMP "$build/libs/eodiisseo.jar"))
        if($process.CommandLine.Contains($knownJar)){$matchesJar=$true}
    }
}
if($process.Name -notmatch '^java(w)?\.exe$' -or !$matchesJar -or $process.CommandLine -notmatch 'server.address=127.0.0.1'){
    throw 'PID does not match the local backend; no process was stopped.'
}
Stop-Process -Id $backendProcessId
Write-Output "Stopped local backend PID $backendProcessId. Database is still running."
