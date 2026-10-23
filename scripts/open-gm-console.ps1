param(
    [string]$Url
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$composeWork = Join-Path $workspace '.local/compose/work'
if (-not $Url) {
    if (Test-Path -LiteralPath (Join-Path $composeWork 'config.json') -PathType Leaf) { $Url = 'http://127.0.0.1:18080/gm' }
    else {
        $configPath = Join-Path $workspace 'config.json'
        $port = 8080
        $scheme = 'http'
        if (Test-Path -LiteralPath $configPath) {
            $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($config.httpServer.bindPort) { $port = $config.httpServer.bindPort }
            elseif ($config.httpServer.port) { $port = $config.httpServer.port }
            if ($config.httpServer.useSSL) { $scheme = 'https' }
        }
        $Url = "${scheme}://127.0.0.1:$port/gm"
    }
}
$uri = [Uri]$Url
if (-not $uri.IsLoopback -or $uri.Scheme -notin @('http', 'https') -or $uri.Query -or $uri.Fragment) {
    throw 'GM 入口只允许无查询参数的本机 HTTP/HTTPS 地址。'
}
Start-Process -FilePath ($Url.TrimEnd('/'))
Write-Host "已打开 GM 控制台：$Url"
