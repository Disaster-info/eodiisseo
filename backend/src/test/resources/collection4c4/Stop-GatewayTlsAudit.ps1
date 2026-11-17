param([Parameter(Mandatory=$true)][string]$Root,[Parameter(Mandatory=$true)][string]$PostgresBin)
$ErrorActionPreference='Stop'
$path=[IO.Path]::GetFullPath($Root)
$base=[IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\'
if(-not $path.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $path -Leaf) -notmatch '^c4c4-gateway-tls-[a-f0-9]{32}$'){throw 'Unexpected cleanup target'}
if(-not(Test-Path -LiteralPath $path)){Write-Output 'DISPOSABLE_TLS_ROOT_ALREADY_ABSENT';return}
if((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse cleanup target refused'}
if(Test-Path "$path\data\postmaster.pid"){
 $pidLines=Get-Content "$path\data\postmaster.pid"
 if($pidLines[3] -ne '55433' -or $pidLines[5] -ne '127.0.0.2'){throw 'Wrong disposable endpoint'}
 $pidText=Get-Content "$path\data\postmaster.pid" -TotalCount 1
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$pidText"
 if($process -and ($process.ExecutablePath -ne (Join-Path $PostgresBin 'postgres.exe') -or -not $process.CommandLine.Replace('/','\').Contains($path.Replace('/','\')))){throw 'Server identity changed'}
 & "$PostgresBin\pg_ctl.exe" stop -D "$path\data" -m fast -w
 if($LASTEXITCODE){throw 'TLS test server did not stop'}
}
if(Test-Path "$path\data\postmaster.pid"){throw 'Still running'}
Remove-Item -LiteralPath $path -Recurse -Force
if(Test-Path $path){throw 'Disposable credential/certificate cleanup failed'}
Write-Output 'DISPOSABLE_TLS_ROOT_REMOVED'
