# 4.6 原始 source2 事件的分组偏好

原始 DifficultyAdjustmentStage 的 42 行包含 57 个 EventIDs。此前只接入 28 个单事件，14 个分组中的其余 29 个实际事件无法选择难度；未选择时仍可按默认世界进入。

本机只读分析定位 DifficultyAdjustModule._InitCanAdjustmentStageEventIDs/585822（ccbfa20），读取每行 EventIDs 并加入实际事件集合；DifficultyAdjustmentStageRow 的 EventIDs 位于 +16。结合已核对的 source2 发送、CanStageEventAdjustDifficulty 和 GetStageEventDifficultyType，服务端采用实际事件 ID 分别保存偏好。配置行 ID 只用于核对原始归属，不把一次选择自动同步给同组其他事件。

固定 TurnBasedGameData 提交 724b139d8c9c32d12552eb95745a4fee72bfe48b 的三份完整 Git 文件证明保存在 all-stage-events-4.6.json：42 条完整难度行、399 条原始 PlaneEvent 和 399 条 StageConfig，共 840 条原始记录。原始 EventIDs 顺序、唯一归属和各事件七档 Mainline 关卡均核对；重复/外来归属、缺档、坏数字和资源变化不能绑定新偏好。原有单事件的来源指纹保持兼容，已有偏好仍核对整份集合后排序查询。

593 项新增 Java 检查覆盖 29 个事件、世界 1 至 6 的 Default/Easy，世界 0 至 6 的未选择默认，对应 14 个组的偏好隔离、原始顺序/归属变化、损坏输入、57 个混合选择的原生查询，以及四种实际 Player/Morphia 完整集合各恢复两次。原实现先失败 368 项，225 项默认与拒绝对照通过，无错误或跳过；593 项及原有 565/50/75 项相关回归已通过；最终全量与独立源码通过数以同 SHA 认证报告为准。

scripts/smoke-stage-event-difficulty.py --all-events 将使用明确的普通场景/正式队伍及实际世界 1 夹具，验证全部 57 事件未选择、显式 Default、Easy 和重启后 Easy 原始关卡/波次。候选已通过 2,748 次原生请求、228 次实际事件入战、四次离线检查（一写三读）、三次正常关服和两次同 UID H2 重启；只有完成的同产物 SHA 报告计入验收，未结束的运行不计完成。原始 20101011 的七档均有两波，原生检查器逐波核对原始怪物顺序、关卡及当前战斗；首次检查器只支持单波而中止的运行排除，不计玩法缺陷。

这补齐原始 source2 表声明的事件难度集合。NPC 放置与入口、自然剧情接取、完整战斗输入/官方能力、所有剧情/宇宙/活动及客户端 UI 仍未完成或验收。时钟乐园原始六剧本的列表/章节字段已做本机静态定位，但当前资源目录未安装剧本 1 引用的 ConfigActivityClockPark/GamePlayPageChapterStoryConfig11.json；本机固定索引已经只读提取其 140 字节原件并完整读取配置字段，继续提取所引用的 523 字节正文，图字段仍需逐项核对；未据 Go 参考的固定成功或静态字段定位制造玩法完成状态。
