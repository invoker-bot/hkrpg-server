param(
    [string]$Url,
    [string]$DataDirectory
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$composeWork = Join-Path $workspace '.local/compose/work'
if (-not $DataDirectory) {
    $DataDirectory = if (Test-Path -LiteralPath (Join-Path $composeWork 'data/gm-console.key')) {
        Join-Path $composeWork 'data'
    } else { Join-Path $workspace 'data' }
}
$keyFile = Join-Path $DataDirectory 'gm-console.key'
if (-not (Test-Path -LiteralPath $keyFile -PathType Leaf)) {
    throw "GM 密钥尚未生成，请先启动新版服务。预期文件：$keyFile"
}
$key = [IO.File]::ReadAllText($keyFile).Trim()
if ($key -notmatch '^[A-Za-z0-9_-]{43}$') { throw 'GM 密钥文件格式错误，请检查服务端日志。' }
if (-not $Url) {
    if ($DataDirectory -eq (Join-Path $composeWork 'data')) { $Url = 'http://127.0.0.1:18080/gm' }
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
    throw '自动登录入口只允许无查询参数的本机 HTTP/HTTPS 地址。'
}
# Fragment is consumed immediately by the page and is never sent as an HTTP URL or printed.
Start-Process -FilePath ($Url.TrimEnd('/') + '#key=' + $key)
Write-Host "已打开 GM 控制台：$Url"
