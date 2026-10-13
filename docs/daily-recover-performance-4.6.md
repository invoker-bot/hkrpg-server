# 3011014 演出入口与临时 NPC

本批补入原始 E 演出的开始回执、开场临时 NPC 的服务端生命周期和缺失入口。**尚不能宣称完整客户端演出通过**：真实游戏客户端没有执行本批相机、动作、捕获、台词分支和中间 NPC 替换。原始演出图保持完整，不把开始确认或任务回报标作已播放证据。

## 固定原图与本机协议

来源仍为 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，版本 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。既有 68 份完整原件及 195 个选中完整对象保留；另将三份完整 DialogueItem 的原始字节、SHA256 和对象指纹保存在 `src/test/resources/fixtures/daily-recover-performance-entrances-4.6.json`。原提交没有 LICENSE/COPYING；这些是来源明确的资源数据，未引入参考代码、可执行程序或运行时依赖，不把它们声明为 AGPL 数据。

`StarPerformanceRelayCsReq` 命令 1224：performance_id 字段 1，performance_type 字段 12；响应 1296 的 retcode 字段 10。固定本机国服 4.6 的 `ELevelPerformanceType` 常量表读取到 E=4。静态读取使用 `scripts/analyze-client-46.py`，证据为 `build/recover-performance-native-20261014.json`，客户端文件 SHA 为 `GameAssembly.dll=8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3`、metadata=`09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130`。字段由冻结描述符检查；将该请求作为本项目开始见证属于服务端实现选择，未取得官方客户端实际发送顺序的证明。

只接通 301101401、402、406—411。未知演出、类型、场景、未接取目标、重复 wire 身份、变化资源和损坏记录明确拒绝。开始不完成目标、不选分支、不发奖，也不扣物。

## 开场 NPC 与恢复

| 原演出 | 楼层 / 组 / 实例 | 服务端开场 NPC |
|---|---|---|
| 301101401 | 10102001 / 99 / 400002 | 原初遇 NPC 已按原加载门存在，不新增副本 |
| 301101402 | 10102001 / 122 / 400001 | 原 11302 临时 NPC |
| 301101406、409 | 20121001 / 94 / 400001 | 原 11302 陪同 NPC |
| 301101407、410 | 20123001 / 75 / 400001 | 原 11302 陪同 NPC |
| 301101408、411 | 10102003 / 23 / 400002 | 原 11302 陪同 NPC |

位置、旋转、实例、NPC 编号和原组从完整匹配资源读取。开始见证先与真实日期、UID、主目标及当前子目标的接取 UUID 同存 Player，再通过现有场景刷新通知发布；重复请求不新增 actor 或再次保存。保存失败恢复原 UUID 和状态，未发布 NPC。完成对应菜单、交物或反馈目标后清理开场 NPC；恢复只接受原始加载组、当前普通剧情场景和仍在进行的同一接取。变更日期、替换接取、离开原楼层或原件损坏不能恢复。

旧版仅报告目标的存档保留兼容；一旦有开始见证，后续回报必须匹配该见证及完整入口。给物/付款跨文档的原人工对账边界仍见 [回收物说明](daily-recover-delivery-4.6.md)。开始历史仅是开始证据；不伪造 EndPerformance、本地图内 CustomString 或客户端演出完成记录。

## 缺失资源与完整画面边界

`scripts/install-recover-delivery-dialogues.py` 按固定完整原件补入 11 份 NPC DialogueMain/Pack 和三份 DialogueItem，共 14 份。全部冲突先核对，创建使用独占文件写入；已有匹配文件保持原字节，冲突拒绝，路径不得逃逸服务端资源目录。不修改 config.json、数据库、已有运营资源或客户端安装。安装报告为 `build/recover-performance-entrances-install-20261014.json`。

G76 的原对话项路径名沿用 `DialogueItem301101101`，其中实际 PerformanceID 和结束字符串指向 301101407，保留原名与原内容。Mission_301101412 中沿用 301101301 的原字符串也保持原件，不为完成界面擅自修改原图。

原 Act 已包含 409 的 400001→400003、410 的说唱动作与 400001→400003、411 的 400002→400004 以及最终退场。**这些中间步骤仍由客户端原图承担，服务端没有逐帧执行它们**。本批没有通用客户端演出解释器，也没有接收任意 CustomString 的新协议。只有实际客户端完整播放、两日三支路及中断重进通过后，才能将“完整演出”改为已验收。

## 验证记录

新增回归先在原实现复现未处理开始请求；修复后验证正常流程、拒绝路径、重复请求、保存失败、原 Player/Morphia 读取、资源缺失及接取替换。完整构建与实际 KCP/H2 结果如下。

- 原实现的 10 项新入口回归均失败，错误/跳过均为 0，保留 `build/recover-performance-20261014/red-xml` 与 `build/recover-performance-red-20261014.log`。修复后的首轮聚合暴露一个测试对异常包装的断言错误，已修正，日志保留；修正后的 10 项专项通过，又追加四项开始后回报与 wire 身份回归，由最终全量构建执行。
- 安装两项冲突/重复运行检查及既有六项探针清理检查，共 8 项 Python 通过。另将 14 份完整文件与固定 Git 原始 blob、fixture 和本机新增文件逐字节独立核对，见 `build/recover-performance-20261014/entrance-git-proof.json`。
- `scripts/build.ps1 -RerunTests` 全量 522 类 / 14,301 项 Java 通过，其中新增演出类 14 项，失败、错误、跳过均为 0，耗时 21 分 12 秒。源码、协议、fixture、构建及验证脚本共 3,449 份输入在执行前后 SHA 相同。XML 副本及聚合证明在 `build/recover-performance-20261014/full-xml`、`full-build-proof.json`，日志为 `build/recover-performance-full-20261014.log`。
- 冻结产物 `build/recover-performance-20261014/runtime.jar`，SHA256 `5bc6c1cf9d77df93bce50370cf33516c9d05262510fa24e62efce7e259da8794`。与原 a760 产物相比新增四个演出状态/生命周期 class，改变五个相关外层 class 与功能目录；47 个嵌套 class 字节发生变化，独立 `javap -c -p -s` 核对执行代码和签名一致。生成协议和其他 class 保持原字节，见 `build/recover-performance-20261014/payload-proof.json`。
- 同 SHA 产物以实际 UDP/KCP 执行三组两日支路：D0 为 403/404/405，D1 为 404/405/403。合计 345 次请求、396 项检查，12 次正常 stop、9 次同 UID H2 进程重启、24 次独立实际 Player/Morphia 读取。开始/重复开始、原开场 NPC 出现、对应目标后释放、真实给物/扣物/5000 信用点与 200 活跃、防重及每日接取身份均通过。并未预写选项、物品、付款或目标完成；只隔离既有前置历史、单任务池及任务/日常时钟。
- 原生全程报告 `build/recover-performance-kcp-20261014.json`，SHA256 `78a139a696daa31cde9c0dd364c1794fbd14ba4f9d976b734c5df0fb50cf4e77`；只读原始日志/存档/工具复核为 `build/recover-performance-kcp-audit-20261014.json`。每组 224 份整文件来源 SHA 稳定，14 份入口在隔离资源中明确复制；报告及工具/原件未在通过后改写。活跃菜单 NPC 的恢复另由真实 Player/Morphia 读取后的场景构建回归验证；原生进程重启矩阵恢复的是已结算阶段与演出开始见证，不宣称演出中途实际客户端重连通过。
- `scripts/smoke-server.py` 同产物两次 HTTP/Dispatch 及同账号恢复通过，见 `build/recover-performance-dispatch-20261014.json`。最初 CLI 调用使用了不支持的参数、原生输出名与静态报告重名，均在启动前拒绝；对应日志保留，不计为验证成功。

自然时间跨日、完整客户端画面、客户端演出过程中断重进、其他 NPC/任务的 E 演出、异常断电原子性和原给物/付款未决收据仍待后续验收。本次提交范围是已验证的服务端入口、开场 NPC 与固定资源安装。
