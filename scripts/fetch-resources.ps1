$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$lock = Get-Content -LiteralPath (Join-Path $workspace 'resources.lock.json') -Raw | ConvertFrom-Json
$destination = Join-Path $workspace 'references\TurnBasedGameData'
if (-not (Test-Path -LiteralPath $destination)) {
    & git clone --depth 1 --filter=blob:none --no-checkout $lock.source $destination
    if ($LASTEXITCODE -ne 0) { throw 'Resource clone failed' }
} else {
    $dirty = & git -C $destination status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Reference resources contain changes; refusing to switch their checkout' }
}
& git -C $destination fetch --depth 1 --filter=blob:none origin $lock.commit
if ($LASTEXITCODE -ne 0) { throw 'Pinned resource fetch failed' }
& git -C $destination sparse-checkout set --no-cone '/ExcelOutput/*' '/Config/LevelOutput/RuntimeFloor/*' '/Config/LevelOutput/RuntimeGroup/*' '/Config/LevelOutput/SharedRuntimeGroup/*' '/Config/ConfigAdventureAbility/LocalPlayer/*' '/README.md'
if ($LASTEXITCODE -ne 0) { throw 'Resource checkout selection failed' }
& git -C $destination checkout --detach $lock.commit
if ($LASTEXITCODE -ne 0) { throw 'Pinned resource checkout failed' }
'/Config/Level/Mission/*', '/Config/Level/Rogue/*', '/Config/Level/RogueDialogue/*', '/Config/Level/LittleGame/*', '/Config/Activity/*', '/Config/BattleLineupPage/SkillTreePointPreset/*', '/Config/ConfigAdventureAbility/*', '/Config/ConfigAdventureModifier/*', '/Config/ConfigSummonUnit/*', '/Config/ConfigMazeBuff/*', '/Config/ConfigEntity/SummonUnit/*' | & git -C $destination sparse-checkout add --stdin
if ($LASTEXITCODE -ne 0) { throw 'Gameplay resource checkout failed' }
Write-Host '固定的 4.6.0 资源下载完成；运行 install-resources.py 安装到独立目录。'
