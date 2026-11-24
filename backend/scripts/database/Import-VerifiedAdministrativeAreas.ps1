param(
    [Parameter(Mandatory=$true)][string]$InputFile,
    [Parameter(Mandatory=$true)][string]$ExpectedSha256,
    [uri]$BaseUri,
    [switch]$Apply
)
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path -LiteralPath $InputFile).Path
$bytes = [IO.File]::ReadAllBytes($path)
$sha = [Security.Cryptography.SHA256]::Create()
try { $digest = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '') }
finally { $sha.Dispose() }
if ($digest -ine $ExpectedSha256) { throw 'The approved payload SHA-256 does not match.' }
$payload = [Text.Encoding]::UTF8.GetString($bytes).TrimStart([char]0xFEFF)
$areas = @((ConvertFrom-Json -InputObject $payload).areas)
if ($areas.Count -lt 1 -or $areas.Count -gt 1000) { throw 'Expected 1 to 1000 areas in one atomic request.' }
$codes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($area in $areas) {
    if ([string]::IsNullOrWhiteSpace($area.regionCode) -or !$codes.Add($area.regionCode)) {
        throw 'Missing or duplicate region code in approved payload.'
    }
    if ($area.active -eq $true -and $area.level -eq 'SIGUNGU' -and
            $area.boundary.type -notin @('Polygon', 'MultiPolygon')) {
        throw 'Every active SIGUNGU must have a reviewed polygon boundary.'
    }
}
if (!$Apply) { return [pscustomobject]@{ rows = $areas.Count; sha256 = $digest; applied = $false } }
if (!$BaseUri -or !$BaseUri.IsAbsoluteUri -or $BaseUri.Scheme -ne 'https' -or
        $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment -or $BaseUri.AbsolutePath -ne '/') {
    throw 'Provide the approved HTTPS API origin.'
}
$token = $env:EODIISSEO_OPS_IMPORT_BEARER
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Set an authorized OPS session token in the process environment.' }
$endpoint = [uri]::new($BaseUri, '/api/v1/ops/admin-areas/import')
try {
    $result = Invoke-RestMethod -Uri $endpoint -Method Post -Body ([Text.Encoding]::UTF8.GetBytes($payload)) `
        -ContentType 'application/json; charset=utf-8' -Headers @{Authorization = "Bearer $token"} `
        -TimeoutSec 60 -MaximumRedirection 0
} catch {
    throw 'The OPS import did not return a success response. Check its status before retrying.'
}
if ($result.importedCount -ne $areas.Count) { throw 'The API count differs from the approved payload. Review database state.' }
[pscustomobject]@{ rows = $result.importedCount; sha256 = $digest; applied = $true }
