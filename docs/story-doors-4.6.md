# 剧情门提前开启修复

## 原因与范围

2026-10-21，用户报告卡芙卡开场大厅的门在操作右侧控制台之前已经开启。只读检查 UID 10001 的位置接近 `P20001/F20001001/G5/300004`，对应大门 `300003`；目标 `100010107` 仍在进行。旧存档只有控制台的 Open 记录，没有对应门的联动记录。

共享加载器存在两个绕过原始状态的临时处理：普通组的所有 `isDoor` 物件被强制 Open；所有 `100000` 控制台定义被写成 `BridgeState3`。后者不在该控制台允许的状态集合内，实体最终落到默认 Closed，连原始 Locked 也丢失。地图组还丢弃了控制台指向具体门的 `ValueSource`。这使开门顺序、交互与任务完成无法对应。

本次删除两处强制状态覆盖，所有门保留资源中的初始状态。原 `Console01_OpenDoor_01` 控制台保留目标绑定，操作仅改变准确绑定的门，控制台与门在同一次玩家保存中写入，保存成功后才通知和推进任务。直接操作有控制台绑定的门不能绕过控制台。未操作的后续门保持关闭；原本 Open 的门仍保持 Open。

## 剧情与旧存档

开场的 `100010105`、`100010107`、`100010111` 使用原始 PropState 目标确定准确楼层、组、控制台和 Open 完成状态。只有已接取、进行中且原任务脚本声明单个 `ChangePropState` 的匹配目标，才能将该 Locked 控制台变为可操作的 Closed；已完成目标恢复 Open。未知脚本和取消目标不能解锁。

固定 4.6 导出脚本的 `ChangePropState` 缺少目标参数。本项目在上述严格匹配范围内，用 PropState 目标补足控制台就绪门槛；这是明确的服务端兼容规则，不声称恢复了完整官方脚本解释器。查阅以下固定公开来源后，所查旧版同文件也没有额外目标参数，没有覆盖运营资源或引入参考仓库运行依赖：

- [4.6 原始资源](https://github.com/DimbreathBot/TurnBasedGameData/tree/724b139d8c9c32d12552eb95745a4fee72bfe48b)，完整测试原件及每文件 SHA 见 `src/test/resources/fixtures/story-doors-4.6.json`。11 份本机资源与固定提交的 JSON 完全相同，文件字节仅有 CRLF/LF 差异；两种摘要见 `docs/story-door-source-evidence.json`。
- [DanHengServerResources](https://github.com/mikugirls/DanHengServerResources/blob/f5a7e7a013e2beabd5ccb16b718ad825f45fa0d3/Config/Level/Mission/1000101/Mission_100010107.json)。
- [DanhengServer-Resources](https://github.com/Luotianyi-0712/DanhengServer-Resources/blob/c86edc60839614a197e196658429ae0e2056bcd9/Config/Level/Mission/1000101/Mission_100010107.json)。
- [hkrpg-go-Resources](https://github.com/gucooing/hkrpg-go-Resources/blob/2abd7d6104c1b48d4c034bb9c016e025c858d7b9/Config/Level/Mission/1000101/Mission_100010107.json)。

旧记录只在精确旧来源摘要匹配时兼容。已完成剧情门保持开启，未完成剧情门不能仅凭旧的单控制台 Open 记录提前开启；再次正常操作可写入新的成对记录。其他不受剧情锁定的控制台保留合法旧进度。没有清空或回滚 UID 10001。

新成对记录可恢复“物件已保存、任务回调尚未保存”的中断窗口；正常刷新补齐匹配目标。只保存单控制台的历史错误记录不会触发这种补算。

## 验证边界

旧产物 `081a980855bfd4f5c646a471da764e11bf71248bfb672f81c4f3d9c35a1fc364` 的项目类配合修正后的测试夹具复现 18 项中的 17 项断言失败，唯一通过的是保持原本开启的门。证据：`build/story-doors-20261021/baseline-final.xml`。此前入口未赋值的测试夹具和两次未正确配置类路径的探针失败均不作为行为失败证据。

另有 1 项旧记录回溯完成、1 项副本状态隔离的先失败后修复检查，分别保留 `backtrack-real-red.xml` 和 `raid-scope-red.xml`。副本继续使用原有主任务接取身份隔离，普通地图的成对记录要求不会替代副本的独立规则。

专项覆盖原始 36 种门的关闭/锁定状态、六扇开场门、前置/取消/脚本改变、错误绑定、跨门顺序、重复交互、存储失败回退、旧记录兼容、中断恢复，以及两次实际 Player/Morphia 读取。原生探针新增 `scripts/smoke-opening-stage-win.py --doors`，使用隔离新号、原始资源、真实 UDP/KCP 和正常 H2 停服重启，不操作运营账号。

完整构建 `scripts/build.ps1 -TestForks 8` 通过：570 个测试类、14,788 项测试，零失败、零错误、零跳过，耗时 8 分 50 秒；构建输入摘要保持不变。最终 19 项剧情门专项和 13 项副本交互回归均通过，汇总见 `docs/story-door-verification.json`。服务更新记录在完成后追加。36 种门的初始状态检查不等于所有门的完整脚本已实现；需要物品、复杂演出、跨剧情线和其他控制图的解锁流程仍按各自支持范围处理，真实客户端动画尚需验收。此前 41 项缺参数的机关宝箱不在本次完成范围内。

## 最终候选产物的原生检查

最终候选 JAR：`1c86fdc448ed95a7f6ec6df86c6bfb17d86a86eec9d6be7515d843532fb27c88`；镜像：`sha256:db8ff6485fe8667e6f1668b8526524aedf2455237c22d5f3b545cc821a4bd8c7`。镜像内 JAR 摘要与隔离网络测试一致，87,472 个资源文件、879,696,733 字节及资源清单摘要 `53d2a1dc1e2942e79afbde6f0262efd7a86f8fabc0129b0bd804f814384c16f7` 保持不变。

同一候选的四阶段请求依次为 48、38、32、31，共 149 次，3 次正常停服、2 次同 UID H2 进程重启。验证首战、教程、可破坏物、普通宝箱，以及首次控制台操作前、首门已开而大厅门未开、重启后操作大厅控制台、再次重启后拒绝重复操作。两个开门目标状态与门状态均通过原生查询核对。

另有隔离 Dispatch 两轮检查，验证账号经过进程重启仍为同一账号。证据分别是 `build/story-doors-20261021/native-opening.json`、`smoke-summary.json` 和 `image-proof.json`。以上均为服务端/网络证据，不代表已经验证真实客户端动画。
