[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^v\d+\.\d+\.\d+(?:[-.][A-Za-z0-9.-]+)?$')]
    [string]$Tag,

    [Parameter(Mandatory)]
    [string]$BaseRef,

    [string]$ReviewBranch
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$official = 'https://github.com/00-Evan/shattered-pixel-dungeon.git'
$tagRef = "refs/noxac/upstream/$Tag"

$base = & git -C $repo rev-parse --verify --end-of-options "${BaseRef}^{commit}"
if ($LASTEXITCODE -ne 0) { throw "Noxac base ref does not identify a commit: $BaseRef" }

if ($ReviewBranch) {
    $branchRef = "refs/heads/$ReviewBranch"
    & git -C $repo check-ref-format $branchRef
    if ($LASTEXITCODE -ne 0) { throw "Invalid review branch name: $ReviewBranch" }
    & git -C $repo show-ref --verify --quiet $branchRef
    if ($LASTEXITCODE -eq 0) { throw "Review branch already exists: $ReviewBranch" }
}

& git -C $repo fetch --no-tags --no-write-fetch-head $official "refs/tags/${Tag}:$tagRef"
if ($LASTEXITCODE -ne 0) { throw "Could not fetch official upstream tag $Tag." }
$upstream = & git -C $repo rev-parse --verify "${tagRef}^{commit}"
if ($LASTEXITCODE -ne 0) { throw "Upstream tag $Tag does not point to a commit." }

& git -C $repo merge-base --is-ancestor $upstream $base
if ($LASTEXITCODE -eq 0) {
    Write-Output "$BaseRef already includes $Tag ($upstream)."
    return
}
if ($LASTEXITCODE -ne 1) { throw 'Git could not compare the upstream and Noxac histories.' }

$preview = @(& git -C $repo merge-tree --write-tree --name-only --messages $base $upstream)
$mergeStatus = $LASTEXITCODE
if ($mergeStatus -eq 1) {
    Write-Output "Merge conflicts for $Tag ($upstream) against $BaseRef."
    if ($preview.Count -gt 1) { $preview[1..($preview.Count - 1)] | Write-Output }
    exit 1
}
if ($mergeStatus -ne 0 -or $preview.Count -eq 0 -or $preview[0] -notmatch '^[a-f0-9]{40,64}$') {
    throw 'Git could not preview the merge. Git 2.38 or newer is required.'
}

Write-Output "Clean merge of $Tag ($upstream) into $BaseRef."
if ($ReviewBranch) {
    $commit = & git -C $repo commit-tree $preview[0] -p $base -p $upstream -m "Merge Shattered Pixel Dungeon $Tag for review"
    if ($LASTEXITCODE -ne 0) { throw 'Git could not create the review merge commit.' }
    & git -C $repo update-ref $branchRef $commit ('0' * $commit.Length)
    if ($LASTEXITCODE -ne 0) { throw "Could not create review branch $ReviewBranch." }
    Write-Output "Created local review branch $ReviewBranch at $commit."
}
