param([ValidateSet('both','dispatch','game','database')][string]$Mode = 'both', [switch]$NoConsole, [switch]$Check, [switch]$NoHandbook)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$localJdk = Get-ChildItem -LiteralPath (Join-Path $workspace '.tools') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
$java = if ($localJdk) { Join-Path $localJdk.FullName 'bin/java.exe' } elseif ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$tcpFallback = Join-Path $workspace '.tools\tcp-fallback'
$arguments = @("-Djdk.net.unixdomain.tmpdir=$tcpFallback", '-jar', 'build/libs/hkrpg-server.jar', "--mode=$Mode")
if ($NoConsole) { $arguments += '--no-console' }
if ($Check) { $arguments += '--check' }
if ($NoHandbook) { $arguments += '--no-handbook' }
Push-Location $workspace
try {
    & $java @arguments
    exit $LASTEXITCODE
} finally { Pop-Location }
