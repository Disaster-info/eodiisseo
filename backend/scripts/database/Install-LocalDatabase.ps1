param([string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'eodiisseo/postgresql17'))
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
$packages = @(
    @{File='postgresql.zip'; Url='https://sbp.enterprisedb.com/getfile.jsp?fileid=1260616'; Hash='80379b2c04d51c30225532e0ae04509899141e9957ed096fe749d7fd9df8f82f'},
    @{File='postgis.zip'; Url='https://download.osgeo.org/postgis/windows/pg17/postgis-bundle-pg17-3.6.2x64.zip'; Hash='7ba180ee2a352987b9a2f194673652c59483b55852295ccf401dceccd8765425'}
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($package in $packages) {
    $archive = Join-Path $InstallRoot $package.File
    if (!(Test-Path -LiteralPath $archive)) { Invoke-WebRequest -UseBasicParsing -Uri $package.Url -OutFile $archive }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash.ToLowerInvariant() -ne $package.Hash) { throw "Archive hash mismatch: $($package.File)" }
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        foreach ($entry in $zip.Entries) {
            $relative = $entry.FullName -replace '^[^/]+/', ''
            if ($relative -notmatch '^(bin|lib|share)/' -or !$entry.Name) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path (Join-Path $InstallRoot 'pgsql') $relative))
            $boundary = [IO.Path]::GetFullPath((Join-Path $InstallRoot 'pgsql')) + [IO.Path]::DirectorySeparatorChar
            if (!$target.StartsWith($boundary,[StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe archive path' }
            New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($target)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
        }
    } finally { $zip.Dispose() }
}
& (Join-Path $InstallRoot 'pgsql/bin/postgres.exe') --version
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL executable check failed' }
Write-Output "Installed PostgreSQL and PostGIS in $InstallRoot. No Windows service was registered."
