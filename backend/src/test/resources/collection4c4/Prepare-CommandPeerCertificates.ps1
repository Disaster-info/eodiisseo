param([Parameter(Mandatory=$true)][string]$Root,[Parameter(Mandatory=$true)][string]$OpenSsl)
$ErrorActionPreference='Stop'
$path=[IO.Path]::GetFullPath($Root);$base=[IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\'
if(-not $path.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $path -Leaf) -notmatch '^c4c4-gateway-tls-[a-f0-9]{32}$' -or -not(Test-Path "$path\ca.key")){throw 'Owned certificate fixture required'}
if(Test-Path "$path\command-client.p12"){throw 'Existing peer fixture is not replaced'}
$ErrorActionPreference='Continue'
& $OpenSsl req -newkey rsa:2048 -nodes -keyout "$path\command-client.key" -out "$path\command-client.csr" -subj '/CN=Disposable-Command-Worker' *> "$path\peer-certificate.log"
if($LASTEXITCODE){throw 'Client CSR failed'}
[IO.File]::WriteAllText("$path\client.ext","extendedKeyUsage=clientAuth`n")
& $OpenSsl x509 -req -in "$path\command-client.csr" -CA "$path\ca.crt" -CAkey "$path\ca.key" -CAcreateserial -out "$path\command-client.crt" -days 1 -extfile "$path\client.ext" *>> "$path\peer-certificate.log"
if($LASTEXITCODE){throw 'Client certificate failed'}
& $OpenSsl pkcs12 -export -inkey "$path\command-client.key" -in "$path\command-client.crt" -certfile "$path\ca.crt" -name worker -out "$path\command-client.p12" -passout "file:$path\bootstrap.password" *>> "$path\peer-certificate.log"
if($LASTEXITCODE){throw 'Client fixture export failed'}
& $OpenSsl pkcs12 -export -inkey "$path\data\server.key" -in "$path\data\server.crt" -certfile "$path\ca.crt" -name gateway -out "$path\command-server.p12" -passout "file:$path\bootstrap.password" *>> "$path\peer-certificate.log"
if($LASTEXITCODE){throw 'Server fixture export failed'}
Write-Output 'DISPOSABLE_COMMAND_MTLS_FIXTURES_READY'
