# 运行与部署

## 运行环境

需要 JDK 21+。当前源码含虚拟线程调用，JDK 17 无法编译该基线。工作区的 `.tools` 中已有独立 JDK 21，未改动系统的 JDK 17。复制工程到其他机器时需自行安装 JDK 21。

Gradle wrapper 固定 8.14.4。编译使用已经入库的 Quickbuf Java 消息，无需下载原始游戏 proto 才能构建，也不在构建时自动生成其他版本协议。

```powershell
./scripts/build.ps1
./scripts/start.ps1 -Mode dispatch -NoConsole
```

构建脚本给当前 Windows 环境设置局部 JDK 网络兼容参数。若其他环境不存在 AF_UNIX 管道问题，直接使用 `./gradlew test shadowJar` 和 `java -jar` 即可。

## 服务模式

| 模式 | 行为 | 是否需要游戏资源 |
|---|---|---|
| both（默认） | HTTP Dispatch/Gateway + 游戏 UDP/KCP + 数据库 | 是 |
| dispatch | SDK 账号、令牌、区域发现、健康接口 | 否 |
| game | Gateway HTTP + 游戏 UDP/KCP + 数据库 | 是 |
| database | 仅启动内置数据库 | 否 |

默认 HTTP 是 `127.0.0.1:8080`，游戏 UDP 是 `127.0.0.1:23301`。首次配置保存在工作目录的 `config.json`；数据与内置 H2 文件保存在 `data/`。默认玩家权限为 `player.*`，管理员指令可通过本地控制台执行或显式授权。

后台运行用 `--no-console`，关闭标准输入不会使它退出。终端运行可使用 `/stop` 或 Ctrl+C；停止时关闭 HTTP、计时器、数据库和文件监听器。Windows 的强制终止进程不会运行 Java shutdown hook，不能代替可靠的停机与备份流程。

## 匹配资源

本机客户端已只读确认是国服 4.6.0。独立资源目录 `resources/4.6` 来自正式版国际服数据快照；服务端加载通过仍不证明国服客户端兼容或全部场景完整。

资源目录必须匹配配置中选择的 `protocol.wireVersion`，目录名为 **ExcelOutput**。4.6 实验配置需合并以下字段到现有 `config.json`：

```json
{"protocol":{"wireVersion":"4.6.0"},"resourceDir":"./resources/4.6"}
```

缺少 `protocol` 字段的旧配置继续选择 4.2.0。不要把 4.6 数据用于旧线协议。

```text
resources/
  resource-manifest.json      可选版本声明
  ExcelOutput/*.json         配置表
  Config/LevelOutput/RuntimeFloor/*.json
  Config/LevelOutput/SharedRuntimeGroup/...
  Config/Level/Mission/...
  Config/Level/Rogue/...
  Config/Level/RogueDialogue/...
  Config/Level/LittleGame/...
  Config/Activity/...
  Config/ConfigAdventureAbility/...
  TextMap/...
```

数据来源为 [DimbreathBot/TurnBasedGameData](https://github.com/DimbreathBot/TurnBasedGameData)，固定提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，声明 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。本次清单核验 69,957 个 JSON；新增 Mission、宇宙/对话、活动/小游戏配置不会自动执行全部脚本。源中仍缺少 169 个地图组引用、2 个剧情脚本和部分秘技配置，不能宣称全地图完成。

```powershell
./scripts/fetch-resources.ps1
python scripts/install-resources.py
python scripts/audit-resources.py
python scripts/smoke-4.6.py
```

安装器仅补缺失文件，发现已有不同内容时停止；新增文件写独立补充清单，保留原有清单与资源。资源数据不打进 jar，不作为源码发布内容。

飞霄、遐蝶已接入的限定秘技还需要两份完整 LocalPlayer 配置。安装器直接读取固定提交中的原始 Git blob，核对完整 SHA 后补缺；稀疏检出无需展开其他角色。当前目录的两份补充与既有文件保护验证见[角色配置安装](maze-player-config-install.md)。

资源来源与版本由 [resources.lock.json](../resources.lock.json) 固定。下载脚本仅处理忽略的参考目录；若其中有未提交改动会停止，不切换带改动的检出。

可添加如下版本声明；这个声明用于阻止明显版本混用，不能证明文件内容属于该版本：

```json
{"clientVersion":"4.6.0"}
```

执行预检会检查已定义的表是否存在及 JSON 结构，检查场景目录和版本声明。部分可空配置表可能需要按真实资源进一步区分必需/可选；预检通过也不代表全部场景引用和玩法语义已验证。

```powershell
./scripts/start.ps1 -Check
./scripts/start.ps1 -NoConsole -NoHandbook
```

默认卡池、活动排期和 RogueMapGen 从 jar 初始化到 `data/`，只在文件缺失时复制，已有自定义配置不会被覆盖。它们继承自上游快照，并不是当前官方活动排期。

## 配置与验证

允许在 `config.json` 中只指定要覆盖的设置，其他字段从默认配置补齐：

```json
{
  "httpServer": {"bindAddress":"127.0.0.1", "bindPort":8080},
  "gameServer": {"name":"My HKRPG Server", "bindPort":23301}
}
```

配置损坏或关键节为 null 时启动失败并保留原文件。`--check` 的 `ready` 只表示静态预检结果；运行中的 `/health/ready` 表示初始化完成，当前尚未做数据库故障的持续探测。

```powershell
python ./scripts/smoke-server.py
```

这个检查启动临时 Dispatch 进程，验证存活、就绪、状态、功能清单、账号创建、令牌验证、combo 交换、非法请求以及重启后 UID 持久化。临时日志和数据库留在 `build/smoke/` 便于检查，不写入真实运行数据。

## 外部数据库与容器

`deploy/compose.yaml` 提供 Java + MongoDB 的本地部署例子。Docker 配置默认将 HTTP/UDP 绑定容器网卡，但端口只发布到宿主机回环地址。外部客户端连接时，需要明确设置对外地址和网络配置。

```sh
docker compose -f deploy/compose.yaml up --build
```

默认以 Dispatch 模式启动。完整游戏模式需要挂载匹配资源，并把启动命令改为 `--mode=both --no-console`。

当前 SDK 密码未验证，新 SDK 登录保留上游固定账号机制；这部分必须在公开提供账号服务前改造。数据库备份与恢复、多节点、性能和完整游戏流程尚未完成验收。
