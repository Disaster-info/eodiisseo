param(
    [Parameter(Mandatory=$true)][string]$InputFile,
    [Parameter(Mandatory=$true)][string]$ExpectedSha256,
    [uri]$BaseUri,
    [switch]$Apply,
    [switch]$AllowLocalDevelopment,
    [string]$ApprovalManifest,
    [string]$ExpectedManifestSha256
)
$ErrorActionPreference = 'Stop'
$path = (Resolve-Path -LiteralPath $InputFile).Path
if ($Apply -and !$path.EndsWith('.approved.json',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Only a reviewed .approved.json file can be applied. Audit and candidate payloads cannot be applied.'
}
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
if (!$Apply) { return [pscustomobject]@{ rows = $areas.Count; bytes = $bytes.Length; sha256 = $digest; applied = $false } }
if (!$BaseUri -or !$BaseUri.IsAbsoluteUri -or
        $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment -or $BaseUri.AbsolutePath -ne '/') {
    throw 'Provide the approved HTTPS API origin.'
}
$target = 'production'
if ($AllowLocalDevelopment) {
    if ($BaseUri.Scheme -ne 'http' -or $BaseUri.Host -ne '127.0.0.1' -or $BaseUri.Port -ne 8081) {
        throw 'The development option accepts only http://127.0.0.1:8081/.'
    }
    $target = 'local-development'
} elseif ($BaseUri.Scheme -ne 'https') { throw 'Production imports require HTTPS.' }
if (!$ApprovalManifest -or $ExpectedManifestSha256 -notmatch '^[0-9a-fA-F]{64}$') {
    throw 'Provide the approval manifest and its independently recorded SHA-256.'
}
$manifestPath = (Resolve-Path -LiteralPath $ApprovalManifest).Path
$manifestBytes = [IO.File]::ReadAllBytes($manifestPath)
$sha = [Security.Cryptography.SHA256]::Create()
try { $manifestDigest = [BitConverter]::ToString($sha.ComputeHash($manifestBytes)).Replace('-', '') }
finally { $sha.Dispose() }
if ($manifestDigest -ine $ExpectedManifestSha256) { throw 'Approval manifest SHA-256 mismatch.' }
$approval = [Text.Encoding]::UTF8.GetString($manifestBytes).TrimStart([char]0xFEFF) | ConvertFrom-Json
if ($approval.status -cne 'APPROVED' -or $approval.target -cne $target -or
        $approval.origin -cne $BaseUri.AbsoluteUri -or $approval.payloadSha256 -ine $digest -or
        $approval.rows -ne $areas.Count -or $approval.bytes -ne $bytes.Length) {
    throw 'The manifest does not approve this payload and origin.'
}
foreach ($field in 'reviewReference','sourceUrl','sourceDate','licenseUrl','transformSha256','provenanceSha256','backupSha256','legacyPlanReference') {
    if ([string]::IsNullOrWhiteSpace($approval.$field)) { throw "Missing approval field: $field" }
}
foreach ($field in 'transformSha256','provenanceSha256','backupSha256') {
    if ($approval.$field -notmatch '^[0-9a-fA-F]{64}$') { throw "Invalid SHA-256 field: $field" }
}
$token = $env:EODIISSEO_OPS_IMPORT_BEARER
if ([string]::IsNullOrWhiteSpace($token)) { throw 'Set an authorized OPS session token in the process environment.' }
$endpoint = [uri]::new($BaseUri, '/api/v1/ops/admin-areas/import')
try {
    if ($AllowLocalDevelopment) {
        Add-Type -AssemblyName System.Net.Http
        $handler = [Net.Http.HttpClientHandler]::new()
        $handler.UseProxy = $false
        $handler.AllowAutoRedirect = $false
        $client = [Net.Http.HttpClient]::new($handler)
        $content = [Net.Http.ByteArrayContent]::new($bytes)
        try {
            $client.Timeout = [TimeSpan]::FromSeconds(120)
            $client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token)
            $content.Headers.ContentType = [Net.Http.Headers.MediaTypeHeaderValue]::new('application/json')
            $response = $client.PostAsync($endpoint,$content).GetAwaiter().GetResult()
            try {
                $null = $response.EnsureSuccessStatusCode()
                $result = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
            } finally { $response.Dispose() }
        } finally { $content.Dispose(); $client.Dispose() }
    } else {
        $result = Invoke-RestMethod -Uri $endpoint -Method Post -Body $bytes `
            -ContentType 'application/json; charset=utf-8' -Headers @{Authorization = "Bearer $token"} `
            -TimeoutSec 120 -MaximumRedirection 0
    }
} catch {
    throw 'The OPS import did not return a success response. Check its status before retrying.'
}
if ($result.importedCount -ne $areas.Count) { throw 'The API count differs from the approved payload. Review database state.' }
[pscustomobject]@{ rows = $result.importedCount; sha256 = $digest; applied = $true }
