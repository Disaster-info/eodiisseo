param([string]$TestFilter='',[string]$BuildOutputDir=(Join-Path $env:TEMP 'eodiisseo-db-build'))
$ErrorActionPreference='Stop'
$backend=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$repo=[IO.Path]::GetFullPath((Join-Path $backend '..'))
$previous=@{}
try {
    $values=@{
        EODIISSEO_AUDIT_JDBC_URL='jdbc:postgresql://127.0.0.1:55433/postgres'
        EODIISSEO_AUDIT_DISPOSABLE='true'
        EODIISSEO_LOCAL_DB_TESTS='true'
        # The other owner's audit startup test creates its own minimal schema.
        # CanonicalDatabaseIntegrationTest explicitly runs the REAL migrations on a separate DB.
        SPRING_FLYWAY_ENABLED='false'
    }
    Get-Content -LiteralPath (Join-Path $repo '.env') | ForEach-Object {if($_ -match '^(JWT_SECRET)=(.*)$'){$values[$matches[1]]=$matches[2]}}
    foreach($key in $values.Keys){$previous[$key]=[Environment]::GetEnvironmentVariable($key,'Process');[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
    Push-Location $backend
    try {
        $options=@('build','--console=plain',"-PbuildOutputDir=$BuildOutputDir")
        if($TestFilter){$options=@('test','--console=plain',"-PbuildOutputDir=$BuildOutputDir",'--tests',$TestFilter)}
        & .\gradlew.bat @options
        if($LASTEXITCODE -ne 0){throw 'Local database verification failed; inspect the Gradle test report.'}
    } finally {Pop-Location}
} finally {foreach($key in $previous.Keys){[Environment]::SetEnvironmentVariable($key,$previous[$key],'Process')}}
