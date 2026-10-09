# 4.6 手机剧情视频完成记录

原始消息表的三段演出 1219000、1219100、1504201 含视频节点 121900015、121910005、150420110。此前服务器把这些 NPC 视频与普通文本一起自动写入完成历史，允许漏交视频记录完成整段。现改为等待视频完成记录，整段回报必须按资源图经过视频节点；批量提交先核对全部段落，任一失败不保存其他段落。普通 NPC 文本仍自动推进。

固定资源提交为 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，72 条完整原始记录/4 份 JSON 留在 `src/test/resources/fixtures/message-video-4.6.json`；每条记录同时保存实际文件 SHA、Git 原件 SHA 与原始路径。消息正文的 TextID 哈希不当作客户端 text_id。协议仍使用既有 4.6 冻结快照，来源 Honey AGPL 固定 `fe00bdbda3c28ca33d01f171566aa35b5ab95083`，未改命令表、生成类或版本常量。

本机 `GameAssembly.dll` 与元数据固定 SHA 只读核对：MessageItemType.Video=5，MustFinish 检查类型 4/5/6，视频历史完成分支发送 2760；演出内完成项进入记录列表，保存演出使用该列表。7 份完整 PE 展开区间/字节 SHA 与元数据方法绑定见 [静态证据](client46-message-video-evidence.json)。原生二进制读取器的字段关联含静态布局推断，没有执行游戏程序，也没有验证所有热修分支。

19 项新增 Java 回归使用三条原始图，覆盖漏视频、末尾 NPC 视频停留、其他段落视频替代、失败整批、正确整批、首/末回复分支、重复和 JSON 重载；其中 9 项在原逻辑上真实断言失败，`build/message3314-original-red.xml`。初次网络测试用错嵌套请求类型，已先纠正后重跑原逻辑；这类夹具失败不累计。既有 6 项消息回归一起检查兼容性。

运行 `python -X utf8 scripts/smoke-message-video.py` 验证实际打包服务的 UDP/KCP。脚本仅在 `build/smoke/message-video-*` 创建唯一账号/资源夹具：原始消息、组、段落和视频表保持原样，接取主任务 199936/三个子任务是明确隔离规则。首次会话依次检查漏视频、跨段替代、正确记录和重复，并检查整批失败没有保存第一段。两次重连中的一份发生在正常 stop 关服/H2 重启后。53 项检查、三份同 UID 快照、两次 stop 和两份 H2/Morphia 独立只读恢复已在原型通过；实际背包不变，信用点为零。主任务保留正常的空奖励计划/零奖励收据，不把它们解释成实际奖励到账。

最终同源码/同 jar 结果只认 `build/message-3333-source-verification.json` 的 `passed: true`；原型结果为 `build/message3329-prototype.json`。最终检查还需全量 3333 Java、12 Python、151 冻结协议、独立源码构建和本批/既有网络链路一致。测试存档读取类不进入运行 jar。原型不代替最终构建，已有旧批证书不冒充本批。

视频记录只能证明客户端提交了正确节点，不能证明视频已实际播放。官方前置/完整 PlayMessage、SaveMessage 解释器、非零文本变体、Raid/Link、跨段脚本、PFMHKKKAGCD 与国服界面仍待验收；正常关服恢复不证明断电原子性。功能状态保持部分完成。


## 资源隔离结论更正

首次夹具说明因硬链接误写原始resource-manifest.json，旧3333证书的资源完整性结论撤回。原件已恢复到此前保存的SHA-256 ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735，说明独立创建并加入真实文件回归。53项视频网络原型已在恢复后重跑；最终改看build/submission-3364-source-verification.json。恢复记录与验证边界见[子任务监视与资源隔离](submission-monitor-4.6.md)。
