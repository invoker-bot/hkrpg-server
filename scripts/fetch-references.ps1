param([switch]$Refresh)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$referenceRoot = Join-Path $workspace 'references'
New-Item -ItemType Directory -Path $referenceRoot -Force | Out-Null
$manifest = Get-Content -LiteralPath (Join-Path $workspace 'references.lock.json') -Raw | ConvertFrom-Json
foreach ($project in $manifest.projects) {
    if ($project.name -eq 'LunarCore-development') { continue }
    $directory = Join-Path $referenceRoot $project.name
    if (!(Test-Path -LiteralPath (Join-Path $directory '.git'))) {
        & git clone --depth 1 $project.url $directory
        if ($LASTEXITCODE -ne 0) { throw "Clone failed: $($project.name)" }
    }
    if (!$Refresh) {
        & git -C $directory fetch --depth 1 origin $project.commit
        if ($LASTEXITCODE -ne 0) { throw "Pinned commit is unavailable: $($project.name)" }
        & git -C $directory switch --detach $project.commit
        if ($LASTEXITCODE -ne 0) { throw "Checkout failed; local changes were preserved: $($project.name)" }
    } else {
        & git -C $directory fetch --depth 1 origin $project.branch
        if ($LASTEXITCODE -ne 0) { throw "Fetch failed: $($project.name)" }
        & git -C $directory switch --detach FETCH_HEAD
        if ($LASTEXITCODE -ne 0) { throw "Checkout failed; local changes were preserved: $($project.name)" }
    }
}
foreach ($project in $manifest.analysisReferences) {
    # 辅助分析源码始终使用已核对提交；不运行其中的二进制或更新测试版常量。
    $directory = Join-Path $referenceRoot $project.name
    if (!(Test-Path -LiteralPath (Join-Path $directory '.git'))) {
        & git clone --depth 1 $project.url $directory
        if ($LASTEXITCODE -ne 0) { throw "Analysis reference clone failed: $($project.name)" }
    }
    & git -C $directory fetch --depth 1 origin $project.commit
    if ($LASTEXITCODE -ne 0) { throw "Analysis reference commit is unavailable: $($project.name)" }
    & git -C $directory switch --detach $project.commit
    if ($LASTEXITCODE -ne 0) { throw "Analysis checkout failed; local changes were preserved: $($project.name)" }
}
$lunar = Join-Path $referenceRoot 'LunarCore'
$legacy = $manifest.projects | Where-Object name -eq 'LunarCore-development'
$legacyDirectory = Join-Path $referenceRoot 'LunarCore-development'
if (!(Test-Path -LiteralPath $legacyDirectory)) {
    & git -C $lunar fetch --depth 1 origin $legacy.commit
    if ($LASTEXITCODE -ne 0) { throw 'Legacy Java reference fetch failed' }
    & git -C $lunar worktree add --detach $legacyDirectory $legacy.commit
    if ($LASTEXITCODE -ne 0) { throw 'Legacy Java reference checkout failed' }
}
& git -C (Join-Path $referenceRoot 'echium-server') submodule update --init --depth 1
if ($LASTEXITCODE -ne 0) { throw 'echium-server submodule download failed' }
Write-Output 'Reference sources are ready. Use audit-references.py to regenerate the inventory after an intentional refresh.'
