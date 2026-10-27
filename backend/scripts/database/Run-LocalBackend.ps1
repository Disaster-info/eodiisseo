param([int]$Port=8080,[switch]$SkipBuild,[string]$BuildOutputDir=(Join-Path $env:TEMP 'eodiisseo-db-build'))
$ErrorActionPreference='Stop'
$backend=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){throw "Port $Port already in use; preserve the existing process."}
Push-Location $backend
try {
    if(!$SkipBuild){& .\gradlew.bat bootJar --console=plain "-PbuildOutputDir=$BuildOutputDir";if($LASTEXITCODE -ne 0){throw 'Build failed'}}
    $jar=Get-ChildItem -LiteralPath (Join-Path $BuildOutputDir 'libs') -Filter '*.jar' | Where-Object {$_.Name -notlike '*-plain.jar'} | Select-Object -First 1
    if(!$jar){throw 'Build the executable JAR first'}
    $java=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin/java.exe'}else{(Get-Command java.exe).Source}
    $logs=Join-Path $backend 'build/local';New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $args=@('-Dfile.encoding=UTF-8','-jar',('"'+$jar.FullName+'"'),'--server.address=127.0.0.1',"--server.port=$Port",'--spring.profiles.active=local','--spring.jpa.hibernate.ddl-auto=validate','--logging.level.root=INFO')
    $process=Start-Process -FilePath $java -ArgumentList $args -WorkingDirectory $backend -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'backend.out.log') -RedirectStandardError (Join-Path $logs 'backend.err.log')
    [IO.File]::WriteAllText((Join-Path $logs 'backend.pid'),[string]$process.Id)
    for($attempt=0;$attempt -lt 60;$attempt++){
        if($process.HasExited){throw "Backend exited. Inspect $logs/backend.out.log and backend.err.log"}
        if(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue){Write-Output "Backend listening at http://127.0.0.1:$Port (PID $($process.Id)).";return}
        Start-Sleep -Milliseconds 500
    }
    throw "Backend did not become ready yet; inspect logs in $logs"
} finally {Pop-Location}
