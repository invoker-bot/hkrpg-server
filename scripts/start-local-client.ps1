[CmdletBinding()]
param(
    [string]$Manifest = '',
    [string]$PythonExecutable = '',
    [switch]$DryRun,
    [switch]$InstallShortcut,
    [switch]$ElevationAttempted,
    [string]$ShortcutPath = (Join-Path ([Environment]::GetFolderPath('Desktop')) 'StarRail.exe.lnk')
)
$ErrorActionPreference = 'Stop'
function Get-ClientFileSHA256([string]$Path) {
    $stream = [System.IO.File]::OpenRead($Path)
    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '') }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
$launcherLog = Join-Path $env:LOCALAPPDATA 'HKRPG\client-launcher.log'
try {
    $launcherDirectory = Split-Path -Parent $PSCommandPath
    if (-not $Manifest) {
        $Manifest = Join-Path $launcherDirectory '..\.local\client\patch-manifest.json'
    }
    $Manifest = (Resolve-Path -LiteralPath $Manifest).Path
    if (-not $PythonExecutable) {
        $PythonExecutable = (Get-Command python.exe -ErrorAction Stop).Source
    }
    $PythonExecutable = (Resolve-Path -LiteralPath $PythonExecutable).Path
    $scriptPath = Join-Path $launcherDirectory 'start-local-client.py'
    $scriptArgs = @('-X', 'utf8', $scriptPath, '--manifest', $Manifest)
    if ($ElevationAttempted) { $scriptArgs += '--elevated-start' }
    if ($DryRun -or $InstallShortcut) { $scriptArgs += '--dry-run' }
    $resultText = (& $PythonExecutable @scriptArgs 2>&1 | Out-String).Trim()
    $launcherExitCode = $LASTEXITCODE
    $result = $resultText | ConvertFrom-Json
    if ($launcherExitCode -ne 0) {
        if ($result.error) { throw $result.error }
        throw "客户端启动检查失败：$resultText"
    }
    if ($InstallShortcut) {
        $clientExe = Join-Path $result.clientRoot 'StarRail.exe'
        $shell = New-Object -ComObject WScript.Shell
        if (-not (Test-Path -LiteralPath $ShortcutPath -PathType Leaf)) {
            throw "没有找到原桌面快捷方式：$ShortcutPath"
        }
        $ShortcutPath = (Resolve-Path -LiteralPath $ShortcutPath).Path
        $shortcut = $shell.CreateShortcut($ShortcutPath)
        $powershellPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $wrapperPath = $PSCommandPath
        $arguments = '-NoLogo -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' +
            $wrapperPath + '" -Manifest "' + $Manifest + '" -PythonExecutable "' + $PythonExecutable + '"'
        $isOriginal = $shortcut.TargetPath -ieq $clientExe -and [string]::IsNullOrEmpty($shortcut.Arguments)
        $isInstalled = $shortcut.TargetPath -ieq $powershellPath -and $shortcut.Arguments -ceq $arguments
        if (-not $isOriginal -and -not $isInstalled) {
            throw '原快捷方式已被其他程序更改，拒绝覆盖。'
        }
        $backup = Join-Path (Split-Path -Parent $Manifest) 'original-StarRail.exe.lnk'
        if ($DryRun) {
            [pscustomobject]@{ dryRun=$true; shortcut=$ShortcutPath; backup=$backup; target=$powershellPath;
                arguments=$arguments; clientCheck=$result } | ConvertTo-Json -Depth 5
            exit 0
        }
        if (Test-Path -LiteralPath $backup) {
            $saved = $shell.CreateShortcut($backup)
            if ($saved.TargetPath -ine $clientExe -or -not [string]::IsNullOrEmpty($saved.Arguments)) {
                throw '已有快捷方式备份不符合原入口，拒绝替换备份。'
            }
            if ($isOriginal -and (Get-ClientFileSHA256 -Path $backup) -cne
                    (Get-ClientFileSHA256 -Path $ShortcutPath)) {
                throw '原快捷方式与已有备份不同，拒绝覆盖。'
            }
        } elseif ($isOriginal) {
            [System.IO.File]::Copy($ShortcutPath, $backup, $false)
        } else {
            throw '入口已经修改但原快捷方式备份缺失，请先恢复备份。'
        }
        # An explicit game icon preserves the old icon even though the target is now PowerShell.
        $shortcut.TargetPath = $powershellPath
        $shortcut.Arguments = $arguments
        $shortcut.WorkingDirectory = (Split-Path -Parent $launcherDirectory)
        $shortcut.IconLocation = $clientExe + ',0'
        $shortcut.WindowStyle = 7
        $shortcut.Description = 'HKRPG 本地客户端：自动检查补丁和服务器'
        try {
            $shortcut.Save()
            $saved = $shell.CreateShortcut($ShortcutPath)
            if ($saved.TargetPath -ine $powershellPath -or $saved.Arguments -cne $arguments) {
                throw '快捷方式保存验证失败'
            }
        } catch {
            [System.IO.File]::Copy($backup, $ShortcutPath, $true)
            throw
        }
        [pscustomobject]@{ installed=$true; shortcut=$ShortcutPath; backup=$backup;
            backupSHA256=(Get-ClientFileSHA256 -Path $backup); clientStarted=$false } | ConvertTo-Json
        exit 0
    }
    if ($DryRun) {
        $resultText
        exit 0
    }
    try {
        [System.IO.Directory]::CreateDirectory((Split-Path -Parent $launcherLog)) | Out-Null
        [System.IO.File]::AppendAllText($launcherLog,
            ([DateTime]::UtcNow.ToString('o') + ' ' + $resultText + [Environment]::NewLine),
            (New-Object System.Text.UTF8Encoding $false))
    } catch { } # A log failure must not report failure after the game was successfully started.
    exit 0
} catch {
    $message = $_.Exception.Message
    if ($DryRun -or $InstallShortcut) {
        Write-Error $message -ErrorAction Continue
    } else {
        try {
            [System.IO.Directory]::CreateDirectory((Split-Path -Parent $launcherLog)) | Out-Null
            [System.IO.File]::AppendAllText($launcherLog,
                ([DateTime]::UtcNow.ToString('o') + ' ERROR ' + $message + [Environment]::NewLine),
                (New-Object System.Text.UTF8Encoding $false))
        } catch { }
        Add-Type -AssemblyName System.Windows.Forms
        [System.Windows.Forms.MessageBox]::Show($message, 'HKRPG 本地客户端',
            [System.Windows.Forms.MessageBoxButtons]::OK,
            [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    }
    exit 1
}
