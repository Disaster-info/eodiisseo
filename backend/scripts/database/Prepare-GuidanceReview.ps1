param([string]$SourceDirectory=(Join-Path $PSScriptRoot '../../build/openstack-guidance-sources'))
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $SourceDirectory).Path
$candidates=Get-Content -LiteralPath "$root/source-candidates.json" -Raw -Encoding UTF8 | ConvertFrom-Json
function Read-ReviewText([string]$html){
    $normalized=($html -replace '<[^>]+>', ' ') -replace '\s+', ' '
    [Net.WebUtility]::HtmlDecode($normalized).Trim()
}
$review=@(foreach($source in $candidates){
    if($source.hazard -notin @('EARTHQUAKE','HEAVY_RAIN','WILDFIRE') -or $source.file -ne "$($source.hazard).html"){
        throw 'Unexpected source candidate'
    }
    $path=Join-Path $root $source.file
    if((Get-FileHash -LiteralPath $path).Hash -ne $source.sha256){ throw "Source hash mismatch for $($source.hazard)" }
    $html=(Get-Content -LiteralPath $path -Raw -Encoding UTF8) -replace '(?s)<!--.*?-->', ''
    $end=$html.IndexOf('<h3 class="f-logo">')
    if($end -lt 0){throw 'Official page footer unavailable'}
    $html=$html.Substring(0,$end)
    $tags=[regex]::Matches($html,'(?s)<(?<tag>h4|button)\b[^>]*class="(?:page-(?:s)?title|tab-list)[^"]*"[^>]*>(?<title>.*?)</\k<tag>>')
    $paragraphs=@(for($i=0;$i -lt $tags.Count;$i++){
        $tag=$tags[$i]
        $heading=Read-ReviewText $tag.Groups['title'].Value
        if(!$heading -or $heading -match '동영상'){continue}
        $start=$tag.Index+$tag.Length
        $until=if($i+1 -lt $tags.Count){$tags[$i+1].Index}else{$html.Length}
        $lists=[regex]::Matches($html.Substring($start,$until-$start),'(?s)<ul\b[^>]*class="comm-list[^"]*"[^>]*>(.*?)</ul>')
        $listNo=0
        foreach($list in $lists){
            $listNo++
            $points=@([regex]::Matches($list.Groups[1].Value,'(?s)<li\b[^>]*>(.*?)(?=<li\b|</li>|$)') | ForEach-Object {Read-ReviewText $_.Groups[1].Value} | Where-Object {$_})
            if(!$points.Count){continue}
            $paragraph=$points -join "`n"
            $hasher=[Security.Cryptography.SHA256]::Create()
            try {
                $bytes=[Text.Encoding]::UTF8.GetBytes($paragraph)
                $sha=[BitConverter]::ToString($hasher.ComputeHash($bytes)).Replace('-','').ToLowerInvariant()
            } finally {$hasher.Dispose()}
            [ordered]@{id="$($source.hazard)-$($i+1)-$listNo"; heading=$heading; locator="heading[$($i+1)] > comm-list[$listNo]"; text=$paragraph; sha256=$sha}
        }
    })
    if(!$paragraphs.Count){throw "Review paragraph extraction failed for $($source.hazard)"}
    [ordered]@{hazard=$source.hazard; agency='행정안전부 국민안전24'; sourceUrl=$source.sourceUrl; sourceFile=$source.file; sourceSha256=$source.sha256; retrievedUtc=$source.retrievedUtc; effectiveDate=$null; revision=$source.sha256; license='PENDING_RIGHTS_REVIEW'; review='PENDING_RIGHTS_AND_CONTENT_REVIEW'; extractionVersion='safekorea-comm-list-review-v1'; paragraphs=$paragraphs}
})
[IO.File]::WriteAllText("$root/guidance-review-paragraphs.json",(ConvertTo-Json -InputObject $review -Depth 10))
$review | ForEach-Object { "$($_.hazard): $($_.paragraphs.Count)" }
Get-FileHash "$root/guidance-review-paragraphs.json" | Select-Object Hash
