param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$localJdk = Get-ChildItem -LiteralPath (Join-Path $workspace '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }
# This Windows environment cannot connect AF_UNIX selector wakeup pipes.
# A nonexistent socket directory makes the JDK use its TCP loopback fallback.
$tcpFallback = Join-Path $workspace '.tools\tcp-fallback'
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=$tcpFallback".Trim()
Push-Location $workspace
try {
    if ($SkipTests) { & .\gradlew.bat shadowJar --no-daemon }
    else { & .\gradlew.bat test shadowJar --no-daemon }
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
} finally { Pop-Location }
