param([Parameter(Mandatory=$true)][string]$Root)
$ErrorActionPreference='Stop'
$path=[IO.Path]::GetFullPath($Root)
$base=[IO.Path]::GetFullPath($env:TEMP).TrimEnd('\')+'\'
if(-not $path.StartsWith($base,[StringComparison]::OrdinalIgnoreCase) -or (Split-Path $path -Leaf) -notmatch '^c4c[34]-(gateway-tls|tls)-[a-f0-9]{32}$'){throw 'Unexpected owned ACL target'}
$me=[Security.Principal.WindowsIdentity]::GetCurrent().User
$system=New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
$items=@((Get-Item -LiteralPath $path))+@(Get-ChildItem -LiteralPath $path -Recurse -Force)
foreach($item in $items){
 if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Reparse ACL target refused'}
 if($item.PSIsContainer){$acl=New-Object Security.AccessControl.DirectorySecurity}else{$acl=New-Object Security.AccessControl.FileSecurity}
 $acl.SetAccessRuleProtection($true,$false)
 foreach($sid in @($me,$system)){
  if($item.PSIsContainer){$rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')}
  else{$rule=New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','Allow')}
  $acl.AddAccessRule($rule)
 }
 $item.SetAccessControl($acl)
 $verified=(Get-Acl -LiteralPath $item.FullName).Access
 if(@($verified | Where-Object {$_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value -notin @($me.Value,$system.Value)}).Count){throw 'Unexpected credential ACL principal'}
}
Write-Output 'OWNED_TLS_FIXTURE_ACL_VERIFIED'