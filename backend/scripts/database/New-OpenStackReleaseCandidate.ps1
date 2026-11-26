param(
    [Parameter(Mandatory=$true)][string]$CandidateDirectory,
    [string]$OutputRoot
)
$ErrorActionPreference='Stop'
$backend=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$repo=Split-Path $backend -Parent
if (!$OutputRoot) { $OutputRoot=Join-Path $backend 'build/openstack-release' }
$source=(Resolve-Path -LiteralPath $CandidateDirectory).Path
$payload=Join-Path $source 'areas.candidate.json'
$provenance=Join-Path $source 'manifest.json'
$payloadHash=(Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash
$provenanceHash=(Get-FileHash -LiteralPath $provenance -Algorithm SHA256).Hash
$review=Get-Content -LiteralPath $provenance -Raw -Encoding UTF8 | ConvertFrom-Json
$template=Get-Content -LiteralPath (Join-Path $PSScriptRoot 'production-area-approval.example.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($payloadHash -ne '81DEC63275B517B611A5358EBEE3FFA515D5239B8B6AB3297D7083F2825F8001' -or
    $provenanceHash -ne '0F966609AE1D85C304377DB34AE1B307F6639A5C46E56ABA00186B7DD178EF72' -or
    $review.candidateSha256 -ne $payloadHash -or $review.candidateRows -ne 285 -or
    $review.candidateBytes -ne (Get-Item -LiteralPath $payload).Length) {
    throw 'Candidate differs from the reviewed 285-area artifact.'
}
if ($template.status -ne 'PENDING' -or $template.target -ne 'production' -or
    $template.payloadSha256 -ne $payloadHash -or $template.provenanceSha256 -ne $provenanceHash -or
    $template.transformSha256 -ne $review.transformSha256 -or $template.rows -ne $review.candidateRows -or
    $template.bytes -ne $review.candidateBytes) { throw 'Production approval template differs from candidate provenance.' }
$out=Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ('release-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
New-Item -ItemType Directory -Path $out | Out-Null
$charset=[Text.UTF8Encoding]::new($false)
$head=& git -C $repo rev-parse HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot identify source HEAD.' }
$status=@(& git -C $repo status --porcelain=v1 -- backend/src/main backend/build.gradle backend/settings.gradle backend/gradle)
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine source status.' }
function Read-SourceInputs {
    @(Get-ChildItem (Join-Path $backend 'src/main') -File -Recurse
      Get-Item (Join-Path $backend 'build.gradle'),(Join-Path $backend 'settings.gradle'),(Join-Path $backend 'gradlew.bat')
      Get-ChildItem (Join-Path $backend 'gradle/wrapper') -File) | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{path=$_.FullName.Substring($backend.Length+1).Replace('\','/');
            sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
    }
}
$before=@(Read-SourceInputs)
$build=Join-Path $out 'build-work'
Push-Location $backend
try {
    $ErrorActionPreference='Continue'
    & .\gradlew.bat bootJar --offline --console=plain "--max-workers=2" "-PbuildOutputDir=$build" *> (Join-Path $out 'build.log')
    $exit=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    if ($exit -ne 0) { throw 'Backend build failed. Inspect build.log.' }
} finally { Pop-Location }
if (Compare-Object $before @(Read-SourceInputs) -Property path,sha256) { throw 'Build inputs changed during the build.' }
$files=@('areas.candidate.json','manifest.json','normalized-names.csv','official-hierarchy-review.csv')
foreach ($name in $files) { Copy-Item -LiteralPath (Join-Path $source $name) -Destination $out }
Copy-Item -LiteralPath (Join-Path $build 'libs/eodiisseo.jar') -Destination $out
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'production-readiness.sql'),(Join-Path $PSScriptRoot 'openstack-inventory.sh'),
    (Join-Path $PSScriptRoot 'Import-VerifiedAdministrativeAreas.ps1'),(Join-Path $repo 'PRODUCTION_DEPLOYMENT_RUNBOOK.md'),
    (Join-Path $repo 'PRODUCTION_INTEGRATION_HANDOFF.md'),
    (Join-Path $PSScriptRoot 'openstack-api-timing-http.conf'),(Join-Path $PSScriptRoot 'openstack-api-timing-location.conf'),
    (Join-Path $PSScriptRoot 'production-area-approval.example.json'),(Join-Path $backend 'docs/openstack-service-cutover.md'),
    (Join-Path $backend 'docs/official-service-activation.md') -Destination $out
$artifacts=Get-ChildItem $out -File | Where-Object Name -ne 'build.log' | Sort-Object Name | ForEach-Object {
    [pscustomobject]@{file=$_.Name;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash}
}
$manifest=[ordered]@{
    status='CANDIDATE_PENDING_RELEASE_APPROVAL'
    sourceHead=$head
    sourceHasUncommittedChanges=($status.Count -gt 0)
    sourceInputs=$before
    flywayLocations=@('classpath:db/migration','classpath:db/owned-runtime')
    optionalRagFlywayLocation='classpath:db/rag-vector'
    createdUtc=[DateTime]::UtcNow.ToString('o')
    artifacts=$artifacts
}
[IO.File]::WriteAllText((Join-Path $out 'release-manifest.json'),($manifest|ConvertTo-Json -Depth 6),$charset)
Write-Output $out
