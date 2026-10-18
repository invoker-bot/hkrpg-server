# 本地客户端补丁的持久启动

登录补丁直接保存在安装目录中的账号 SDK、SDK 资源包和 Dispatch 配置，不需要 Codex 会话或转发进程。2026-10-18 重新核对安装清单的五个文件，摘要全部一致；安装前原桌面 `StarRail.exe.lnk` 直接指向原安装 EXE；本轮已备份原入口并安装下述固定检查脚本，游戏 EXE 和图标保持原路径。

调试窗口使用游戏根目录的 `version.dll`。检查时仅剩三份 `version.dll.<数字>`，摘要均为已验证版本，原加载文件名缺失。现有控制台源码、构建和安装工具没有改名逻辑；尚未取得能确定改名进程的文件操作记录，因此不将原因归于特定软件。启动器解决下一次启动前文件名缺失的问题，不修改游戏保护模块。

## 安装资料

稳定目录使用仓库的 `.local/client/`，不要将唯一备份放在可清理的 `build/`。该目录需要：

- `patch-manifest.json`：下面的清单。
- `version.dll`：从已验证构建复制的调试 DLL，SHA256 为 `323299a6320f30fd9ad5f5595878d8efd49fc5444d4f51521c679cb59f63b9d2`。
- 安装入口后自动保存 `original-StarRail.exe.lnk`，保留原快捷方式字节内容。

清单结构如下，所有摘要必须来自已核对安装记录；不能用当前未知文件自动生成并信任：

```json
{
  "schema": 1,
  "clientRoot": "C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current",
  "clientFiles": {
    "StarRail.exe": "<已核对 SHA256>",
    "UnityPlayer.dll": "<已核对 SHA256>",
    "GameAssembly.dll": "<已核对 SHA256>",
    "StarRail_Data/Plugins/x86_64/AccountPlatNative.dll": "<已核对 SHA256>",
    "StarRail_Data/StreamingAssets/MiHoYoSDKRes/PC/mihoyo_sdk_res": "<已核对 SHA256>",
    "StarRail_Data/StreamingAssets/ClientConfig.bytes": "<已核对 SHA256>",
    "StarRail_Data/il2cpp_data/Metadata/global-metadata.dat": "<已核对 SHA256>"
  },
  "debugConsole": {
    "sha256": "323299a6320f30fd9ad5f5595878d8efd49fc5444d4f51521c679cb59f63b9d2",
    "backupPath": "version.dll"
  },
  "service": {
    "readyUrl": "http://127.0.0.1:18080/health/ready",
    "startCommand": "cd D:\\Projects\\Experiment\\hkrpg-server; docker compose --env-file .local/compose/compose.env -f deploy/compose.local.yaml up -d --wait"
  }
}
```

`clientFiles` 必须恰好包含七项。五项来源是 `build/login-datagram-20261017/installed-files-before.json`，EXE 和 UnityPlayer 来源是 `scripts/build-client-console.py` 的已核对摘要。服务必须返回 HTTP 200 和 JSON `ready: true`；启动器不使用系统代理、不跟随健康检查重定向。

## 检查与安装

在仓库根目录使用已安装的 Python 3.13：

```powershell
$python = 'C:\Users\InvokerBot\.pyenv\pyenv-win\versions\3.13.13\python.exe'
& $python scripts/start-local-client.py --dry-run
& $python scripts/start-local-client.py --repair-only
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-local-client.ps1 -PythonExecutable $python -InstallShortcut -DryRun
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/start-local-client.ps1 -PythonExecutable $python -InstallShortcut
```

第一步只读检查；第二步只恢复调试 DLL，不启动游戏。后两步分别预览和安装原桌面快捷方式。安装保留原路径、名称及游戏图标，将目标改为隐藏的 PowerShell 启动检查；失败会显示错误窗口并写入 `%LOCALAPPDATA%\HKRPG\client-launcher.log`。安装快捷方式自身只执行只读检查，不恢复 DLL、不启动客户端。原快捷方式备份不可覆盖；发现其他目标或不一致备份时会拒绝修改。

之后仍双击原桌面图标。服务器未就绪时会提示 Compose 启动命令；七项文件任一变化时拒绝启动并保留现状。仅当 `version.dll` 缺失时，才从摘要匹配的稳定备份或数字后缀副本恢复；临时完整文件校验后原子发布，发布不会覆盖并发出现的文件。客户端运行时拒绝修复；已有不同摘要的 `version.dll` 不覆盖。游戏启动后检查脚本退出，游戏调试窗口由游戏进程自己的线程维护，不依赖常驻脚本。

撤回入口时先退出游戏，将 `.local/client/original-StarRail.exe.lnk` 复制回原桌面 `StarRail.exe.lnk`。这仅恢复启动方式；登录补丁和客户端文件不随入口撤回改变。

## 验证边界

先保留 13 项隔离回归中的 6 项失败，再加入恢复流程；另用 Windows PowerShell 5.1.26100.7019 的真实 WScript 快捷方式读写复现并修复短路径/长路径比较以及备份文件扩展名问题。最终 16 项检查通过，覆盖重复改名、只读预览、仅修复、并发不覆盖、运行中拒写、错误文件摘要、健康状态、快捷方式连续安装及省略 Manifest 参数的默认入口。默认清单路径在脚本正文中计算，避免 PowerShell 5.1 参数初始化期间脚本目录为空。证据位于 `build/client-launcher-persistence-20261018/`。

测试使用临时目录中的文本伪客户端和本地健康检查替身，不启动真实游戏。实际客户端下一次启动后的调试窗口和进入地图表现仍需用户确认。
