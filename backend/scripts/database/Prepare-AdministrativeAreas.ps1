param(
    [string]$SourceRoot = (Join-Path $env:LOCALAPPDATA 'eodiisseo/reference-data/admin-areas'),
    [string]$PostgresBin = (Join-Path $env:LOCALAPPDATA 'eodiisseo/postgresql17/pgsql/bin'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../../build/admin-area-audit'),
    [string]$OfficialCodeFile,
    [string]$ExpectedOfficialCodeSha256,
    [switch]$ServiceCandidateOnly
)
$ErrorActionPreference = 'Stop'
if ($ServiceCandidateOnly -and !$OfficialCodeFile) { throw 'A service candidate requires the official code file.' }
$utf8 = [Text.UTF8Encoding]::new($false, $true)
$root = (Resolve-Path -LiteralPath $SourceRoot).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
if ($output -eq $root -or $output.StartsWith($root + '\',[StringComparison]::OrdinalIgnoreCase)) {
    throw 'The output must be outside the reference data directory.'
}
New-Item -ItemType Directory -Force -Path $output | Out-Null
$output = Join-Path $output ('run-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output | Out-Null
$database = 'area_' + [guid]::NewGuid().ToString('N')
$psql = Join-Path $PostgresBin 'psql.exe'
$connection = @('-X','-w','-h','127.0.0.1','-p','55433','-U','eodiisseo_audit','-v','ON_ERROR_STOP=1')
$sourceName = 'AL_D001_00_20261109(SIG)'
$shape = Join-Path $root "vworld/sigungu/$sourceName.shp"
$geojson = Join-Path $root 'converted/vworld_sigungu_4326.geojson'
$sido = Join-Path $root 'analysis/sido.csv'
$vworldManifest = Join-Path $root 'vworld-sha256.csv'
$originalManifest = Join-Path $root 'source-sha256.csv'
function Confirm-SourceHash([string]$Path, [string]$Manifest) {
    $hits = @(Import-Csv -LiteralPath $Manifest -Encoding UTF8 | Where-Object {
        $_.Algorithm -eq 'SHA256' -and
        ($_.Path -replace '\\','/').EndsWith('/' + ($Path.Substring($root.Length+1) -replace '\\','/'),[StringComparison]::OrdinalIgnoreCase)
    })
    if ($hits.Count -ne 1 -or (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ine $hits[0].Hash) {
        throw "Source hash mismatch or missing manifest entry for $(Split-Path $Path -Leaf)"
    }
}
function SqlPath([string]$Path) {
    if ($Path.Contains("'") -or $Path.Contains("`n") -or $Path.Contains("`r")) { throw 'Unsupported path.' }
    return $Path.Replace('\','/')
}
function Invoke-AuditSql([string[]]$Arguments) {
    & $psql @connection @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Audit PostgreSQL command failed.' }
}
$primary = @('shp','shx','dbf','prj','cpg') | ForEach-Object {
    $file = [IO.Path]::ChangeExtension($shape,$_)
    Confirm-SourceHash $file $vworldManifest
    $file
}
$zip = Join-Path $root "vworld/$sourceName.zip"
Confirm-SourceHash $zip $vworldManifest
$releaseZip = Join-Path $root 'vworld/AL_D001_00_20261109.zip'
Confirm-SourceHash $releaseZip $vworldManifest
$sidoSource = @(Get-ChildItem -LiteralPath (Join-Path $root 'sido') -File)
$sidoSource | ForEach-Object { Confirm-SourceHash $_.FullName $originalManifest }
$inputs = @($primary) + @($sidoSource.FullName) + @($releaseZip,$zip,$geojson,$sido,$vworldManifest,$originalManifest)
if ($OfficialCodeFile) {
    $OfficialCodeFile = (Resolve-Path -LiteralPath $OfficialCodeFile).Path
    $codeDigest = (Get-FileHash -LiteralPath $OfficialCodeFile -Algorithm SHA256).Hash
    if ($codeDigest -ine $ExpectedOfficialCodeSha256) { throw 'Official code SHA-256 mismatch.' }
    $inputs += $OfficialCodeFile
}
$inputHashes = Get-FileHash -LiteralPath $inputs -Algorithm SHA256
$loaderSql = Join-Path $output 'load-shape.sql'
$loader = Start-Process -FilePath (Join-Path $PostgresBin 'shp2pgsql.exe') -WindowStyle Hidden -Wait -PassThru `
    -ArgumentList @('-s','5186','-W','CP949','-D',('"' + $shape + '"'),'raw_sigungu') `
    -RedirectStandardOutput $loaderSql -RedirectStandardError (Join-Path $output 'shapefile-loader.log')
if ($loader.ExitCode -ne 0) { throw 'shp2pgsql failed.' }
$identity = Invoke-AuditSql @('-d','postgres','-Atqc',"select current_user || ':' || inet_server_port()")
if ($identity -ne 'eodiisseo_audit:55433') { throw 'Unexpected Audit instance identity.' }
Invoke-AuditSql @('-d','postgres','-qc',"create database $database")
try {
    Invoke-AuditSql @('-d',$database,'-qc','create extension postgis')
    Invoke-AuditSql @('-d',$database,'-qf',$loaderSql)
    $setup = Join-Path $output 'compare.sql'
    $sidoPath = SqlPath $sido
    $geoPath = SqlPath $geojson
    $transform = SqlPath (Join-Path $PSScriptRoot 'administrative-area-transform.sql')
    $diagnosticMode = (!$ServiceCandidateOnly).ToString().ToLowerInvariant()
    $sql = @"
\set detailed_analysis $diagnosticMode
CREATE TABLE source_sido(ctprvn_cd text PRIMARY KEY, ctp_eng_nm text, ctp_kor_nm text NOT NULL);
\copy source_sido FROM '$sidoPath' CSV HEADER
CREATE TABLE supplied_geojson(document jsonb NOT NULL);
\lo_import '$geoPath'
\set geo_oid :LASTOID
INSERT INTO supplied_geojson SELECT convert_from(lo_get(:geo_oid::oid),'UTF8')::jsonb;
SELECT lo_unlink(:geo_oid::oid);
\i '$transform'
"@
    if ($OfficialCodeFile) {
        $officialCsv = Join-Path $output 'official-active.csv'
        $officialRows = [IO.File]::ReadLines($OfficialCodeFile,[Text.Encoding]::GetEncoding(949)) | ForEach-Object {
            $fields = $_ -split "`t"
            if ($fields.Count -ge 3 -and $fields[0] -match '^\d{10}$' -and
                    $fields[2].Trim() -eq [string]([char]0xC874)+[char]0xC7AC -and $fields[0].EndsWith('00000')) {
                [pscustomobject]@{code=$fields[0]; name=$fields[1].Trim()}
            }
        }
        if (@($officialRows).Count -lt 200) { throw 'Expected active official legal-area text export.' }
        $officialRows | Export-Csv -LiteralPath $officialCsv -Encoding UTF8 -NoTypeInformation
        $officialPath = SqlPath $officialCsv
        $sql += @"

CREATE TABLE official(code text PRIMARY KEY, name text NOT NULL);
\copy official FROM '$officialPath' CSV HEADER
CREATE VIEW official_diff AS
WITH legal AS (
 SELECT CASE WHEN right(code,8)='00000000' THEN left(code,2) ELSE left(code,5) END code,name FROM official
), geometry_codes AS (
 SELECT code,full_name AS name FROM chosen UNION ALL SELECT code,name FROM derived_sido
)
SELECT coalesce(g.code,l.code) AS code,g.name AS boundary_name,l.name AS official_name,
 CASE WHEN g.code IS NULL THEN 'missing_boundary' WHEN l.code IS NULL THEN 'missing_official_code'
 ELSE 'name_mismatch' END AS issue
FROM geometry_codes g FULL JOIN legal l USING(code) WHERE g.name IS DISTINCT FROM l.name;
CREATE VIEW official_hierarchy_review AS
SELECT c.code,c.full_name,c.parent AS payload_parent,left(o.code,5) AS intermediate_city,o.name AS city_name
FROM chosen c JOIN official o ON left(o.code,4)=left(c.code,4)
  AND right(o.code,6)='000000' AND left(o.code,5)<>c.code
  AND starts_with(substring(c.full_name FROM position(' ' in c.full_name)+1),
                  substring(o.name FROM position(' ' in o.name)+1) || ' ');
CREATE TABLE service_city AS
SELECT h.intermediate_city AS code,left(h.intermediate_city,2) AS parent,
       substring(h.city_name FROM position(' ' in h.city_name)+1) AS name,
       max(c.attribute_date) attribute_date,
       ST_Multi(ST_UnaryUnion(ST_Collect(c.geom ORDER BY c.code))) AS geom
FROM official_hierarchy_review h JOIN chosen c ON c.code=h.code
GROUP BY h.intermediate_city,h.city_name;
DO `$`$
BEGIN
 IF (SELECT count(*) FROM service_city) <> 13 OR
    (SELECT count(*) FROM official_hierarchy_review) <> 39 OR
    (SELECT count(*) FROM chosen c JOIN official o ON o.code=c.code || '00000') <> 256 OR
    EXISTS (SELECT 1 FROM service_city WHERE geom IS NULL OR ST_IsEmpty(geom) OR NOT ST_IsValid(geom)) THEN
   RAISE EXCEPTION 'Service hierarchy or legal codes differ from the reviewed snapshot';
 END IF;
END `$`$;
CREATE VIEW service_import_rows AS
SELECT i.code,CASE WHEN c.code IS NULL THEN i.area ELSE i.area || jsonb_build_object(
    'name',CASE WHEN h.intermediate_city IS NULL THEN substring(o.name FROM position(' ' in o.name)+1)
                ELSE substring(o.name FROM length(h.city_name)+2) END,
    'parentRegionCode',coalesce(h.intermediate_city,c.parent)) END AS area
FROM import_rows i LEFT JOIN chosen c ON c.code=i.code
LEFT JOIN official o ON o.code=c.code || '00000'
LEFT JOIN official_hierarchy_review h ON h.code=c.code
UNION ALL
SELECT code,jsonb_build_object('regionCode',code,'name',name,'parentRegionCode',parent,
    'active',true,'level','SIGUNGU','sourceProvider','VWORLD_SIG_CITY_DERIVED','sourceAreaCode',code,
    'sourceUrl','https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=21','baseYear',2026,
    'sourceUpdatedAt',attribute_date::text || 'T00:00:00+09:00',
    'latitude',ST_Y(ST_PointOnSurface(geom)),'longitude',ST_X(ST_PointOnSurface(geom)),
    'boundary',ST_AsGeoJSON(geom,16,0)::jsonb)
FROM service_city;
"@
    }
    $queries = [ordered]@{
        'complexity.csv' = 'select code,vertices,parts,octet_length(area::text) as json_bytes from import_rows order by vertices desc'
        'location-check.csv' = "select code from (select code,geom from chosen union all select code,geom from derived_sido) t where ST_Covers(geom,ST_SetSRID(ST_MakePoint(127.033,37.738),4326)) order by code"
    }
    if (!$ServiceCandidateOnly) {
        $queries['geometry-diagnostics.csv'] = 'select * from geometry_diagnostics order by code'
        $queries['overlap-summary.csv'] = 'select * from overlap_summary order by method'
        $queries['overlap-pairs.csv'] = 'select * from overlap_pairs order by method,area_m2 desc,code_a,code_b'
        $queries['overlap-top.csv'] = "select * from overlap_pairs where method='transformed_linework' order by area_m2 desc,code_a,code_b limit 21"
    }
    if ($OfficialCodeFile) {
        $queries['official-differences.csv'] = 'select * from official_diff order by code'
        $queries['official-hierarchy-review.csv'] = 'select * from official_hierarchy_review order by code'
        $queries['normalized-names.csv'] = "select c.code,c.full_name as source_name,o.name as official_name,i.area->>'name' as service_name from chosen c join official o on o.code=c.code || '00000' join service_import_rows i on i.code=c.code where c.full_name<>o.name order by c.code"
        $queries['service-complexity.csv'] = "select code,octet_length(area::text) as json_bytes from service_import_rows order by json_bytes desc"
    }
    foreach ($name in $queries.Keys) {
        $target = SqlPath (Join-Path $output $name)
        $sql += "`n\copy ($($queries[$name])) TO '$target' CSV HEADER"
    }
    $payload = Join-Path $output 'areas.audit.json'
    $sql += "`n\pset format unaligned`n\pset tuples_only on`n\o '$(SqlPath $payload)'`n"
    $sql += "SELECT jsonb_build_object('areas',jsonb_agg(area ORDER BY length(code),code)) FROM import_rows;`n\o`n"
    if ($OfficialCodeFile) {
        $candidate = Join-Path $output 'areas.candidate.json'
        $sql += "\o '$(SqlPath $candidate)'`nSELECT jsonb_build_object('areas',jsonb_agg(area ORDER BY length(code),code)) FROM service_import_rows;`n\o`n"
    }
    [IO.File]::WriteAllText($setup,$sql,$utf8)
    Invoke-AuditSql @('-d',$database,'-qf',$setup)
    $versions = Invoke-AuditSql @('-d',$database,'-Atqc','select version(),postgis_full_version()')
    $evidence = [ordered]@{
        status='AUDIT_ONLY'; sourceRelease='2026-11-09'; sourceCrs='EPSG:5186'; crs='EPSG:4326'; encoding='CP949'
        sourceUrl='https://www.vworld.kr/dtmk/dtmk_ntads_s002.do?svcCde=NA&dsId=21'
        derivedSido='VWORLD_SIG_DERIVED, ST_UnaryUnion of canonical repaired SIGUNGU by code prefix'
        repair='ST_Transform to EPSG:4326, ST_MakeValid linework, polygon extraction, ST_Multi. No simplification or overlap clipping.'
        officialCodeCheck=if($OfficialCodeFile){'see official-differences.csv'}else{'NOT_AVAILABLE'}
        license='CC BY 2.0 KR, VWorld catalog dsId=21, full release file sequence 2732'
        licenseUrl='https://creativecommons.org/licenses/by/2.0/kr/'
        toolVersions=$versions
        transformSha256=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'administrative-area-transform.sql')).Hash
        inputs=$inputHashes; outputSha256=(Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash
        outputBytes=(Get-Item -LiteralPath $payload).Length
    }
    if ($OfficialCodeFile) {
        $evidence.candidateSha256=(Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash
        $evidence.candidateBytes=(Get-Item -LiteralPath $candidate).Length
        $evidence.candidateRows=285
    }
    [IO.File]::WriteAllText((Join-Path $output 'manifest.json'),($evidence | ConvertTo-Json -Depth 6),$utf8)
    Write-Output $output
}
finally {
    Invoke-AuditSql @('-d','postgres','-qc',"drop database $database with (force)")
    Remove-Item -LiteralPath $loaderSql -ErrorAction SilentlyContinue
}
