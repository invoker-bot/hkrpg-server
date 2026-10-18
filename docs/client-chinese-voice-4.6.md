# 4.6 中文语音补包记录

2026-10-18，按用户要求先补下载中文语音。已向当前客户端的 `StarRail_Data/Persistent/Audio/AudioPackage/Windows/Chinese(PRC)/` 安装 **173 个音频包和 173 个校验标记**，共 **12,413,965,968 字节**。346 个文件均通过文件大小、MD5 和 SHA256 核验。机器可读记录见 [中文语音证据](client46-chinese-voice-evidence.json)。

## 下载来源与操作范围

使用已安装的 `hoyo-env` 下载器，源码提交为 `475f163860424adfa2c57cacebc92d975885ad54`，本轮未修改该工具。4.6.0 的资源索引来自 HoyoFiles；选择其中 `zh-cn` 分类，清单和分块由官方 `autopatchcn.bhsr.com` 提供。清单压缩大小、解压大小与发布的 MD5 先行核对，之后逐个校验完整文件。索引、清单和下载器模块摘要均记录在证据文件中。

当前 `hoyo` 的星穹铁道配置没有启用外置语音下载，普通安装只覆盖主清单。因此本次指定中文 Sophon 分类，复用其分块下载、续传和内容缓存，再以独立副本补入中文目录。没有对当前客户端调用 `install`、`switch` 或 `set language`：这些命令的整目录物化可能覆盖已有补丁并清除主清单以外的文件。

下载完成后，重新校验缓存，再写入客户端的新文件；已有文件若不同则拒绝覆盖。客户端副本不与缓存共用硬链接，便于后续游戏自行更新。过程没有启动游戏。

## 安装登记与持久保存

已备份 `StarRail_Data/Persistent/AudioLaucherRecord.txt`，保留原 `English(US)` 行并追加 `Chinese`。这是已安装语言列表；当前语音选择文件 `AudioLangRedord.txt` 仍为 `English`，文字语言和 SDK 设置也未修改。

登记路径和中文名称分别核对了 [Collapse 的星铁安装实现](https://github.com/CollapseLauncher/Collapse/blob/main/CollapseLauncher/Classes/InstallManagement/StarRail/StarRailInstall.cs) 与 [Starward 的语音列表读写实现](https://github.com/Scighost/Starward/blob/main/src/Starward/Features/GameInstall/GamePackageService.cs)。只参考文件格式，没有导入或运行这些项目的代码。

持久资料保存在 `.local/client/chinese-voice-4.6.0/`，包括原安装列表、来源清单、346 个文件的完整摘要及最终收据；`hoyo` 的持久缓存另保存 `hkrpg/manifests/4.6.0.zh.json`。运行中间文件在 `build/chinese-voice-20261018/`。语音文件和缓存不会依赖 Codex 会话存活。

## 验证边界

- 七项客户端补丁摘要在安装和登记后均匹配可信清单，英文目录的文件清单、大小和修改时间保持一致。
- 客户端 `config.ini`、主版本清单、`hoyo` 状态、当前语音选择及音频增量配置均保持原值；已有配置唯一变化是上述安装语言列表。
- 原启动器的只读检查通过，确认本地服务就绪、七项客户端文件完整、使用独立日志窗口，旧进程内调试 DLL 未恢复。
- 本轮是资源安装和文档记录，没有修改服务端运行代码，也没有重跑 Java 全量测试。

这里确认的是 **4.6.0 发布清单中的中文基础语音完整**。现有英文目录另有两个 `External_del_4.6_chapter5_*` 剧情增量包，文件记录的创建时间为 2026-09-30，不在同版基础分类清单内；本轮没有核验对应中文增量，不能据此声称后续所有热更新音频已经齐全。

游戏内简体中文文字、中文语音选择及实际播放仍需客户端确认；进图转圈、模拟宇宙弹窗和 3011014 临时 NPC、完整演出的客户端验收继续保留为待办。本次资源校验不代替这些验收。
