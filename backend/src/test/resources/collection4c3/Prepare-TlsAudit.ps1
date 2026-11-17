param([Parameter(Mandatory=$true)][string]$Root,[Parameter(Mandatory=$true)][string]$PostgresBin,[Parameter(Mandatory=$true)][string]$OpenSsl)
$ErrorActionPreference='Stop'
$Root=[IO.Path]::GetFullPath($Root)
$base=[IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\'
if(-not $Root.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $Root -Leaf) -notmatch '^c4c3-tls-[a-f0-9]{32}$' -or (Test-Path $Root)){throw 'New disposable TEMP directory required'}
if(Get-NetTCPConnection -LocalPort 55435 -State Listen -ErrorAction SilentlyContinue){throw 'Port 55435 occupied'}
if((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory*1KB -lt 512MB){throw 'Insufficient disposable test memory'}
New-Item -ItemType Directory $Root|Out-Null
$pw=Join-Path $Root 'bootstrap.password'
$bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create();$rng.GetBytes($bytes);$rng.Dispose()
[IO.File]::WriteAllText($pw,[Convert]::ToBase64String($bytes));[Array]::Clear($bytes,0,$bytes.Length)
& "$PostgresBin\initdb.exe" -D "$Root\data" -U c4c3_bootstrap --pwfile=$pw --auth-host=scram-sha-256 --auth-local=scram-sha-256 --encoding=UTF8 --no-locale *> "$Root\init.log"
if($LASTEXITCODE){throw 'Isolated initdb failed'}
$ErrorActionPreference='Continue'
& $OpenSsl req -x509 -newkey rsa:2048 -nodes -keyout "$Root\ca.key" -out "$Root\ca.crt" -days 2 -subj '/CN=Disposable-C4C3-CA' *> "$Root\certificate.log"
if($LASTEXITCODE){throw 'CA generation failed'}
& $OpenSsl req -newkey rsa:2048 -nodes -keyout "$Root\data\server.key" -out "$Root\server.csr" -subj '/CN=127.0.0.1' *>> "$Root\certificate.log"
if($LASTEXITCODE){throw 'CSR generation failed'}
[IO.File]::WriteAllText("$Root\server.ext","subjectAltName=IP:127.0.0.1`nextendedKeyUsage=serverAuth`n")
& $OpenSsl x509 -req -in "$Root\server.csr" -CA "$Root\ca.crt" -CAkey "$Root\ca.key" -CAcreateserial -out "$Root\data\server.crt" -days 1 -extfile "$Root\server.ext" *>> "$Root\certificate.log"
if($LASTEXITCODE){throw 'Server certificate generation failed'}
& $OpenSsl req -x509 -newkey rsa:2048 -nodes -keyout "$Root\wrong-ca.key" -out "$Root\wrong-ca.crt" -days 2 -subj '/CN=Disposable-Untrusted-CA' *>> "$Root\certificate.log"
if($LASTEXITCODE){throw 'Untrusted fixture CA generation failed'}
@"
port=55435
listen_addresses='127.0.0.1,::1'
ssl=on
ssl_cert_file='server.crt'
ssl_key_file='server.key'
shared_buffers='32MB'
max_connections=20
log_statement='none'
log_min_error_statement='panic'
log_parameter_max_length=0
log_parameter_max_length_on_error=0
"@|Add-Content "$Root\data\postgresql.conf"
@"
hostssl all all 127.0.0.1/32 scram-sha-256
hostssl all all ::1/128 scram-sha-256
hostnossl all all 127.0.0.1/32 reject
hostnossl all all ::1/128 reject
"@|Set-Content "$Root\data\pg_hba.conf"
$start=Start-Process -FilePath "$PostgresBin\pg_ctl.exe" -ArgumentList @('start','-D',('"'+$Root+'\data"'),'-l',('"'+$Root+'\server.log"'),'-w') -WindowStyle Hidden -PassThru
if(-not $start.WaitForExit(60000)){throw 'TLS helper did not exit'}
if($start.ExitCode){throw 'Isolated TLS server failed to start'}
Write-Output 'DISPOSABLE_TLS_READY_55435'
