# 本地 Compose 常驻部署

本机游戏服务使用 `deploy/compose.local.yaml`，项目名固定为 `hkrpg-local`。它将已经验证的 Java 21 JAR 封装为运行镜像，启动登录、网关和游戏服务；`deploy/compose.yaml` 保留为早期独立 Dispatch 示例。

## 数据与启动

第一次迁移前，先正常停止旧服务，再执行：

```powershell
python scripts/prepare-compose.py --operator-dir <旧运行目录> --jar <已验证的JAR>
docker compose --env-file .local/compose/compose.env -f deploy/compose.local.yaml up -d --build --wait
```

准备脚本只接受尚不存在的目标目录，将现有配置、内置数据库、运行数据和配套 SDK 私钥复制到 `.local/compose/`。资源按逐文件 SHA256 清单固化到镜像的 Linux 文件系统，避免 Windows 共享目录数万次小文件读取造成启动延迟。容器根目录只读，只有运行数据目录和临时目录可写。旧运行目录保留作回退，不改用空的外部 MongoDB；不能同时让旧 Java 进程和 Compose 占用同一端口或写同一数据库。

日常管理均在仓库根目录执行：

```powershell
# 查看状态与日志
docker compose --env-file .local/compose/compose.env -f deploy/compose.local.yaml ps
docker compose --env-file .local/compose/compose.env -f deploy/compose.local.yaml logs --tail 100 -f server

# 正常停止 / 重新启动
docker compose --env-file .local/compose/compose.env -f deploy/compose.local.yaml stop
docker compose --env-file .local/compose/compose.env -f deploy/compose.local.yaml up -d --wait
```

HTTP 保持 `127.0.0.1:18080`，游戏 UDP 保持 `127.0.0.1:23311`；内置数据库不对宿主发布端口。容器使用 `restart: unless-stopped` 和正常退出宽限时间。关闭启动终端或 Codex 会话不会停止服务；主动 `stop` 后需再次 `up -d`。Docker 引擎必须处于运行状态，Windows 关机期间服务不可用。当前 Docker Desktop 已启用随 Windows 登录启动；本轮没有重启 Windows 或整个 Docker 引擎。容器显式保留 `Asia/Singapore` 时区，登录返回的时区仍为 8。

`.local/compose/` 是正式运行数据，不是可随意清理的构建缓存；备份时先正常停服并备份该目录，同时保留 `.local/client/`、仓库代码和原 `resources/4.6/` 资源；后者用于核验并重建镜像。不要执行全局 `docker system prune` 或删除其他项目的容器/卷。

## 客户端补丁

原桌面 StarRail 图标作为固定入口。登录、SDK 资源和 Dispatch 补丁保存在客户端文件中，配套私钥由 Compose 只读挂载，不依赖 Codex 进程或临时代理。启动检查会核对安装文件摘要，并在已知调试 DLL 被改名后从固定备份恢复；遇到版本或摘要不符会停止并给出提示，不覆盖未知版本文件。

安装、回退与校验清单详见[补丁持久启动说明](client-launcher-persistence.md)。

客户端更新可能替换补丁目标，不能把当前版本的校验结果当作未来版本兼容承诺。游戏启动和画面验收由用户操作。

## 验证边界

部署验证包括配置转换、原数据保留、健康检查、CN/OS Dispatch 和网关、宿主到容器的 UDP 连接，以及容器正常停止和重建后的账号/玩家身份连续性。Java 业务逻辑沿用已通过全量回归的产物。

客户端实际开场播放、稳定进图和任务 3011014 完整演出仍需继续验收；Compose 生命周期通过不等于这些游戏画面已经通过。

## 2026-10-18 本机部署记录

已从 `build/sdk-managed-login-20261016/server/` 复制到 `.local/compose/`，原配置和数据库摘要保持不变；另留 `build/compose-migration-20261018/source-backup/` 回退副本。正式实例是 `hkrpg-local-server-1`。LunaGC 容器按用户选择保留，所有 HKRPG 一次性探针使用 Compose `run --rm`，验收结束仅剩一个健康的正式服务容器。

- JAR SHA256：`bce6e60ccbab07a028eb242464ad2c7e6f00bcf2b4c2853aaf58b90ad3253093`；本轮未改 Java 或协议。构建门禁成功，Gradle 复用先前全量 545 类 / 14,471 项结果，未声称重新执行这些用例；本轮另外执行了两轮独立 Dispatch 冒烟。
- 本轮专项测试全部通过：20 项部署测试、19 项验收器测试、16 项客户端启动测试，共 55 项；日志为 `build/compose-migration-20261018/compose-final-tests.log` 和 `client-final-tests.log`。
- 最终镜像：`sha256:aa305f72f7409940cd0b8a4705ad3918f17fdd5ff21e300c08415c0daf0f6ee2`。70,922 个资源文件共 765,711,578 字节，镜像构建逐文件和总数校验通过。
- 三轮独立测试账号初始化各 143 个请求、88 个已实现响应、30 个后续契约通过；55 个未实现查询保持原边界。耗时分别 2,023、2,088、2,022 毫秒，均未放宽原 4 秒限制。
- 正常停止/启动与强制重建后，同一账号使用三次不同密码仍恢复相同玩家身份。两次 SIGTERM 分别约 1.56/1.71 秒退出，退出码 143、无 OOM、关停日志无错误；没有将不存在的专用关停日志标记当作证据。
- 原桌面图标已安装固定检查入口，原快捷方式字节完整备份；七个客户端文件摘要匹配，已知调试 DLL 恢复成功。没有启动游戏或代替用户完成画面验收。

首次容器尝试的失败记录保留：Windows 资源挂载导致任务资料加载明显变慢，资源加载完毕后又因默认 IPv6 通配地址与既有 IPv4 绑定检查不一致退出。原生资源镜像及显式 IPv4 修复后健康启动通过；楼层加载从约 13.9 秒降到 0.85 秒。容器默认 UTC 导致登录时区由 8 变 0，也已先复现再修复为原时区。

主要证据：`build/compose-migration-20261018/lifecycle-final.json`、`deployment-proof.json`、`resource-snapshot.json`、`compose-image-resources-up.log`；失败基线保留在 `compose-up.log`、`first-container.log`、`wildcard-red.log`、`timezone-red.log`。可用 `scripts/verify-compose.py --output build/<新文件名>.json` 作只读复查；仅显式加 `--lifecycle` 才会创建独立测试账号并停启/重建本项目服务。
