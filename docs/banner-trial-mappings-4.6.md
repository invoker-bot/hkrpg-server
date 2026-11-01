# 28 个角色活动卡池的试用映射与进入、退出

## 原因与范围

2026-11-01 对当前 139 份角色活动池配置与固定 4.6 资源的只读审计中，111 份能通过原算法，28 份被关闭。缺口集中在以下 10 个实际活动面板，并不是缺少 28 份完整的试用关卡：

| 卡池编号 | 原始活动面板 | 卡池数 |
|---|---|---:|
| 2092–2098 | 20070、20071 | 7 |
| 2105–2112 | 20075、20076 | 8 |
| 2113–2115 | 20077、20078 | 3 |
| 2128–2134 | 20086、20088 | 7 |
| 2139–2141 | 20091、20092 | 3 |

这些活动的 `ActivityAvatarDemo.TypeParam` 已声明完整关卡集合，`ActivityConfig.ActivityModuleIDList` 声明组合模块及复用的独立模块；旧 `ActivityModuleDemo` 不能为所有成员提供逐角色对应。原代码要求每个关卡都能逐一匹配，导致整个活动关闭。部分共享五星还同时存在于两份原始面板，单一面板的限制也不能表示这种关系。

`TrialActivityCatalog` 现对能完整逐一匹配的活动保留原来的模块选择；旧索引未覆盖完整集合时，按原始活动模块组派生时间窗，不从编号、排列顺序或角色编号猜测缺失成员的独立模块。旧索引在同一活动中明确冲突仍拒绝。多份面板仅在当前 UP 的实际关卡编号集合完全相同时同时保留；同角色不同试用关卡仍视为歧义，不挑选其中一份。

仅开放实际有效且当前开放的角色池及其当前 UP 关卡，四星集合必须完整一致。共享活动里的其他未开放五星不会因此获得试用资格。原始解锁前置、运营显式窗口优先、关闭/过期卡池限制及旧常驻试用规则保留。没有启用运营文件中的历史卡池，没有修改原始资源、玩家进度、胜负或奖励数量。

组合组内有模块被运营显式配置时，不再派生该组其余窗口来绕过手动设置；有效的显式窗口继续按原有活动关卡集合生效。

## 来源与回归

固定来源为 `resources/4.6/ExcelOutput/` 的 `ActivityAvatarDemo.json`、`ActivityConfig.json`、`ActivityModuleDemo.json`、`AvatarDemoConfig.json`、`ActivityPanel.json`、`ItemConfigAvatar.json` 与 `AvatarConfig.json`。`src/test/resources/fixtures/banner-trial-mappings-4.6.json` 保存完整源文件 SHA256、映射所需投影及内置 139 份角色池的对应关系；角色数值和实际场景另由隔离完整服务读取原始资源验证。

本机固定客户端 Lua 的 `RoleTrialActivityPanel._setup_trial_avatar_list` 直接从活动 `TypeParam` 枚举试用关卡；`_on_btn_enter` 以选中关卡的 `StageID` 发起请求。读取已安装客户端的 Lua 索引，逐条验证记录 MD5 并完整解析相关字节码，证据在 `build/banner-trial-activity-lua-evidence.json` 和 `build/banner-trial-lua/`。仅只读分析，没有操作或修改游戏客户端。

先加入 143 项回归，原实现有且仅有这 28 份卡池的正向映射失败，其余 111 份及负向边界通过。真实行为失败日志及 XML 为 `build/banner-trial-mappings-red.log`、`build/banner-trial-mappings-red.xml`；首轮测试夹具的编译错误独立保留在 `build/banner-trial-mappings-red-compile.log`，不计入行为失败。随后补入跨面板关卡歧义、关闭状态及旧模块明确冲突的保护检查。

## 网络验证与实机边界

`scripts/smoke-banner-trials.py` 使用独立账号、端口和 H2，逐次只开放一个待验证角色池，不配置任何显式试用窗口。测试号的任务 1000510 完成与开场剧情试用清理仅为明确隔离前置，准备器拒绝运营数据库目录。

每池检查原生命令返回的活动面板、模块和完整时间窗，再对其五星及三名四星分别执行原生进入、查询场景/队伍、退出及查询原场景/队伍。核对原始入口、实际 NPC、按性别筛选后的准确试用队伍、试用角色类型及原返回位置；每池后核对钱包、物品和正式角色不变。整个过程不构造战斗胜利或领奖；最后正常 H2 重启复查同 UID 的原场景、队伍及空试用记录。

原生探针验证不替代真实客户端界面、动画和全部角色战斗能力验收。历史卡池仍按原来的运营启停状态生效，GM 启用相应卡池后才派生对应试用入口。

最终产物已完成全部 28 个池、112 组进入/退出，覆盖 33 个不同关卡；共 706 项显式网络请求检查，三次正常停服。该轮为世界等级 0、男性开拓者的独立测试号；保存的原场景位置、完整普通队伍、钱包、物品和正式角色保持，正常 H2 重启后仍无试用运行、伪造通关或领奖记录。报告为 `build/banner-trial-mappings-native.json`，日志为 `build/banner-trial-mappings-native-final.log`。

同一产物另通过景元的既有胜利结算与实际领奖回归：24 项显式请求、三次正常停服，实际星琼 20 及两种材料增量准确，通关/已领奖通知、防重和正常 H2 重启保留。报告为 `build/banner-trial-mappings-settlement-native.json`；专项脚本增加可选报告路径，避免覆盖前次交付证据。两份最终网络报告的 JAR SHA256 均为 `82cde3e195ff2270d2a3db7981d79e60988be4c3ca6c37b8d8a33aec9a1ec024`。

首轮进退探针使用了旧协议枚举名称作断言，记录在 `build/banner-trial-mappings-native-first.log`；改为固定 4.6 的真实枚举名后，预验证与最终产物均重新完整执行通过，未把探针断言错误算成映射修复证据。前述 139 项回归使用内置卡池名册；本次 28 项 UP 名单已与当前运营文件逐项核对一致。运营池 2002 的自定义四星名单与内置默认不同，保持原值，比较记录在 `build/banner-trial-mappings-operator-comparison.json`。

`scripts/build.ps1 -TestForks 8` 全量测试及打包通过：643 类 / 15,992 项，失败、错误和跳过均为 0，耗时 13 分 27 秒。新增映射类 146 项全部通过，含 139 份内置卡池及七项负向保护；原目录、试用生命周期、恢复和奖励检查同样通过。汇总及日志在 `build/banner-trial-mappings-full-tests.json` 和 `build/banner-trial-mappings-full-build.log`。镜像构建核对全部资源文件数及逐文件摘要，候选 JAR 与两份最终网络验证一致。

## 本地部署

用户退出游戏后，已将同一验证产物部署到 `hkrpg-local-server-1`。实现提交为 `8160541`，实际运行 JAR SHA256 为 `82cde3e195ff2270d2a3db7981d79e60988be4c3ca6c37b8d8a33aec9a1ec024`。验证包和镜像另行固定保存，未把同时进行的其他工作区修改带入本次部署。

正常停服后备份整个运行目录，核对全部复制文件；旧 JAR、原清单及旧镜像均保留。备份和验证记录位于 `build/banner-trial-mappings-deploy-20261101/`。上线后默认只读 Compose 验证、HTTP/Dispatch、网关、UDP/KCP 心跳和容器健康通过；运行镜像、JAR、两份最终网络报告和全量打包摘要一致。

通过正式服务内部 Mongo 端口只读比对，UID 10001 的试用运行状态、全部通关和领奖收据、星琼、相关材料及物品文档数保持一致；12 份运营配置/记录摘要不变，没有在正式账号新建通关或领奖。证据为部署目录的 `backup.json`、`preservation.json`、`affected-stop.json`、`affected-after.json` 与 `compose-final.json`。真实游戏界面和新卡池的画面操作仍由用户验证；相应卡池按原配置在 GM 启用后，对应试用才会开放。
