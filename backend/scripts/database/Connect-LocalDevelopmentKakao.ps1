$ErrorActionPreference = 'Stop'
if ($env:EODIISSEO_OPS_IMPORT_BEARER) { throw 'Finish the existing local import session first.' }
$clientId = $env:KAKAO_REST_API_KEY
if ($clientId -notmatch '^[a-fA-F0-9]{32}$') { throw 'Set the registered development Kakao REST client ID in this process.' }
$redirect = 'http://127.0.0.1:8082/'
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $bytes = New-Object byte[] 32
    $random.GetBytes($bytes)
    $state = [BitConverter]::ToString($bytes).Replace('-','')
} finally { $random.Dispose() }
$authorize = 'https://kauth.kakao.com/oauth/authorize?response_type=code&prompt=login&client_id=' +
    [uri]::EscapeDataString($clientId) + '&redirect_uri=' + [uri]::EscapeDataString($redirect) + '&state=' + $state
$listener = [Net.HttpListener]::new()
$listener.Prefixes.Add($redirect)
Add-Type -AssemblyName System.Net.Http
$handler = [Net.Http.HttpClientHandler]::new()
$handler.UseProxy = $false
$handler.AllowAutoRedirect = $false
$client = [Net.Http.HttpClient]::new($handler)
$client.BaseAddress = [uri]'http://127.0.0.1:8081/'
$client.Timeout = [TimeSpan]::FromSeconds(15)
try {
    $listener.Start()
    Write-Host 'Open http://127.0.0.1:8082/start and sign in with the designated development Kakao account.'
    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    $loginStarted = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        $pending = $listener.GetContextAsync()
        if (!$pending.Wait($deadline - [DateTime]::UtcNow)) { break }
        $context = $pending.GetAwaiter().GetResult()
        try {
            $context.Response.Headers['Cache-Control'] = 'no-store'
            $context.Response.Headers['Referrer-Policy'] = 'no-referrer'
            if ($context.Request.HttpMethod -cne 'GET') { $context.Response.StatusCode = 405; continue }
            if ($context.Request.Url.AbsolutePath -ceq '/start' -and !$loginStarted) {
                $loginStarted = $true
                $context.Response.Redirect($authorize)
                continue
            }
            if (!$loginStarted -or $context.Request.Url.AbsolutePath -cne '/' -or
                $context.Request.QueryString['state'] -cne $state) {
                $context.Response.StatusCode = 400
                continue
            }
            $code = $context.Request.QueryString['code']
            if (!$code -or $context.Request.QueryString['error']) {
                $context.Response.StatusCode = 400
                throw 'Kakao login was cancelled or did not return an authorization code.'
            }
            $body = @{code=$code;redirectUri=$redirect} | ConvertTo-Json -Compress
            $content = [Net.Http.StringContent]::new($body,[Text.Encoding]::UTF8,'application/json')
            try { $response = $client.PostAsync('api/v1/auth/kakao',$content).GetAwaiter().GetResult() }
            finally { $content.Dispose() }
            try {
                if ([int]$response.StatusCode -notin 200,201) {
                    $context.Response.StatusCode = 502
                    throw "The development backend rejected Kakao login (HTTP $([int]$response.StatusCode))."
                }
                $session = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                if (!$session.accessToken) { throw 'The backend did not issue a session.' }
                $client.DefaultRequestHeaders.Authorization =
                    [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$session.accessToken)
                $me = $client.GetAsync('api/v1/members/me').GetAwaiter().GetResult()
                try {
                    if ([int]$me.StatusCode -ne 200) {
                        $context.Response.StatusCode = 502
                        throw 'The newly authenticated session could not be verified.'
                    }
                    $member = $me.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                    $memberId = [guid]$member.memberId
                    if ($memberId -eq [guid]::Empty) { throw 'The backend did not identify the authenticated member.' }
                    $env:EODIISSEO_OPS_IMPORT_BEARER = $session.accessToken
                    $context.Response.ContentType = 'text/html; charset=utf-8'
                    $page = [Text.Encoding]::UTF8.GetBytes('<!doctype html><title>Development login</title><script>history.replaceState(null,"","/")</script>You can close this tab.')
                    $context.Response.ContentLength64 = $page.Length
                    $context.Response.OutputStream.Write($page,0,$page.Length)
                    return [pscustomobject]@{memberId=$memberId; loginStatus=[int]$response.StatusCode}
                } finally { $me.Dispose() }
            } finally { $response.Dispose() }
        } finally { $context.Response.Close() }
    }
    throw 'The local login window expired.'
} finally {
    $listener.Close()
    $client.Dispose()
}
