# HKRPG Server

HKRPG Server 是基于 LunarCore `lite` 的《崩坏：星穹铁道》开源服务端，使用 Java 21，面向本地运行和玩法开发。

游戏核心沿用 **4.2.0** 内部协议，独立的实验适配层面向**国服 4.6.0**。实机已确认可以登录、继续开场对白并在进图后操作。剧情、玩法和界面按功能逐项验收。 [客户端进图记录](docs/client-loading-wait-diagnostics-4.6.md#2026-10-19-真实客户端进图验收)

## 快速开始

准备 JDK 21，在仓库根目录执行命令。构建产物为 `build/libs/hkrpg-server.jar`。

### 启动 Dispatch

`dispatch` 模式无需游戏资源，可检查账号登录、区服发现和 HTTP 服务。

Windows PowerShell

```powershell
.\scripts\build.ps1
.\scripts\start.ps1 -Mode dispatch -NoConsole
```

Linux 和 macOS

```sh
./gradlew test shadowJar
java -jar build/libs/hkrpg-server.jar --mode=dispatch --no-console
```

默认在 `http://127.0.0.1:8080/health/ready` 查看就绪状态。启动游戏服务前，先在运行 Dispatch 的终端按 Ctrl+C 停止它。

### 启动游戏服务

准备与 `protocol.wireVersion` 匹配的游戏资源。4.6.0 的示例使用 `resources/4.6/`。首次配置可使用示例作为 `config.json`，已有配置时合并版本和资源目录字段。 [资源准备](docs/running.md#匹配资源) · [4.6.0 配置示例](configs/client-4.6.example.json)

以下命令在预检通过后启动完整服务。

Windows PowerShell

```powershell
.\scripts\start.ps1 -Mode both -Check
if ($LASTEXITCODE -eq 0) {
    .\scripts\start.ps1 -Mode both -NoConsole -NoHandbook
}
```

Linux 和 macOS

```sh
java -jar build/libs/hkrpg-server.jar --mode=both --check &&
    java -jar build/libs/hkrpg-server.jar --mode=both --no-console --no-handbook
```

直接启动时，默认 HTTP 端口为 `8080`，游戏 UDP 端口为 `23301`，均绑定 `127.0.0.1`。 [4.6.0 客户端连接](docs/client-launcher-persistence.md)

需要常驻运行时，可采用本地 Docker Compose 部署。该方案将运行配置和存档保存在 `.local/compose/`，本机 HTTP 和游戏 UDP 端口分别为 `18080` 和 `23311`。 [Compose 部署与备份](docs/docker-compose-local.md)

## 功能概况

- **玩家与成长**　账号登录、存档、编队、角色和装备培养、背包、邮件、商店与跃迁。
- **地图与战斗**　场景加载、锚点传送、宝箱与机关，以及部分剧情任务和战斗结算。
- **挑战与活动**　花萼、角色试用及经典模拟宇宙等已有部分流程。

各项功能的实现范围、已知缺口和专项验证记录见 [功能覆盖与验收状态](docs/features.md)。

## 本地管理

游戏服务启动后，可从 HTTP 端口的 `/gm` 打开网页 GM 控制台。使用本地 Compose 时，入口为 `http://127.0.0.1:18080/gm`。Windows 也可双击 `GM控制台.cmd`。控制台提供玩家管理、资源发放、邮件、卡池、商店和活动排期。 [GM 控制台使用说明](docs/gm-console.md)

## 文档

- 服务模式、配置、数据目录与数据库部署。 [运行与部署](docs/running.md)
- 复查登录、切图或互动卡点时的日志位置和核对步骤。 [客户端与服务端日志](docs/game-diagnostics.md)
- 源码结构、协议适配与贡献要求。 [项目架构](docs/architecture.md) · [4.6.0 协议说明](docs/protocol-4.6.md) · [贡献指南](AGENTS.md)

## 来源与许可证

项目基于 LunarCore `lite`，采用 AGPL-3.0。上游出处、引用的协议定义及各自的固定提交见第三方来源说明。游戏客户端和配套资源需单独准备。 [许可证](LICENSE) · [第三方来源](NOTICE.md)
