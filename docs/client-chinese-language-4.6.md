# 4.6 本机中文文字与配音设置修复

后续结果（2026-10-19）：用户已确认文字变成中文；配音仍为英文且不可选。按现用音频索引发现基础语音分类外还缺两份章节包，已补装并保存中文配音选择，详见[完整音频检查](client-audio-completeness-4.6.md)。下文保留前次操作的历史范围。

2026-10-19 用户确认进入游戏仍为英文。安装中文语音包只增加可用音频；本机 `LocalTextLanguage` 和 `LocalAudioLanguage` 选择项都不存在，SDK 当前语言仍为 `en`。最新启动日志先设置 `zh-cn`，在 `SDKLoginPhase._InitSDK` 又切回 `en`。设计数据目录当时只有英文文字文件，缺少简体中文文件。

## 已完成操作

- 从官方 CDN 下载并新增 `StarRail_Data/Persistent/DesignData/Windows/cn/f96da886871b002d752da7a11bf96a14.bytes`，53,981,236 字节，MD5 `f96da886871b002d752da7a11bf96a14`，SHA-256 `880d6c4c8db5dd3c1a8bb82e3b20831ef5692f02351455fa7c2b0fdaa89b3b41`。
- 校验该文件与现用 D16728363 索引一致。索引 SHA-256 保持 `5a5981c06b0d7d1cbff5f89b8450fefb40278c60a5ca10b092b3381094dfcbed`，没有安装新版索引、Lua 或整包更新。
- 重新逐文件校验现有 **346 份基础中文音频文件**的长度与 SHA-256；英文音频和现有游戏文件保留。
- 客户端停止时，仅备份和修改 `HKCU\Software\miHoYo\崩坏：星穹铁道` 下三个语言值，均为 NUL 结尾的 UTF-8 `REG_BINARY`。独立进程读回确认已持久保存。

| 设置值 | 保存内容 |
| --- | --- |
| `LanguageSettings_LocalTextLanguage_h2764291023` | `cn` |
| `LanguageSettings_LocalAudioLanguage_h882585060` | `cn` |
| `MIHOYOSDK_CURRENT_LANGUAGE_h2559149783` | `zh-cn` |

语言格式参考 Collapse 的 [文字设置](https://github.com/CollapseLauncher/Collapse/blob/main/CollapseLauncher/Classes/GameManagement/GameSettings/StarRail/RegistryClass/LocalTextLanguage.cs)（文件 Git blob `47475e2da3764f1ea98d1e72212e6807d0bdb752`）和 [配音设置](https://github.com/CollapseLauncher/Collapse/blob/main/CollapseLauncher/Classes/GameManagement/GameSettings/StarRail/RegistryClass/LocalAudioLanguage.cs)（`282e000ddb8c881c78ab6012bc41ddafa2e7c82f`）。同时只读核对本机原生 `LanguageSettings` 的文字、配音 getter/setter 与字面量；未引入参考代码或运行其程序。

官方文件来源：

`https://autopatchcn.bhsr.com/design_data/V4.6Live/output_16781997_e08da629c47c_912892ce6e2c82/client/Windows/cn/f96da886871b002d752da7a11bf96a14.bytes`

地址由匿名官方更新接口取得，查询格式参考 [FetchHotfix](https://github.com/Hiro420/FetchHotfix/blob/main/fetch.ts)。虽然 CDN 目录属于较新的更新批次，该文件的长度与 MD5 完全匹配已固定的现用索引；只增加这一文件，不改变本地服热更新地址。

## 验证及恢复

持久证据保存在 `.local/client/chinese-language-20261019/`：`receipt.json`、`download.json`、`independent-readback.json`，以及 `language-before-20261018T160645.json`。备份只包含上述三个语言值的原始类型、字节和是否存在。恢复时应先退出游戏：原来存在的值按备份类型、字节还原，原来不存在的两个选择值删除；无需删除已校验的中文文件。不得导入空的整项注册表或覆盖其他游戏设置。

安装前后 **七个客户端补丁文件 SHA-256 均匹配原清单**，原桌面入口、独立调试窗口、Compose 和账号数据均未更改。原有 `AudioLangRedord.txt` 留给游戏按新选择更新，没有猜测其中枚举值。操作未启动客户端。

设计数据检查器同时修复了漏查语言子目录的问题，19 项针对性回归通过；`scripts/build.ps1` 成功，Java 测试及 JAR 任务为 `UP-TO-DATE`，未宣称重新执行全部 Java 测试。本轮是本地客户端资源与设置修复，没有服务端代码变更或部署。

## 尚待实际客户端确认

需从原桌面 StarRail 入口启动，确认文字、配音及场景操作。资源和注册表校验不能代替游戏画面验收。

用户进一步说明现象为场景动画继续播放，但加载转圈始终遮挡操作。旧日志已到达 `AdventurePhase._OnLoadingFinish`；地形 LOD 缺失路径有原生默认值分支，角色外观空引用发生在退出清理时，均不能直接认定为转圈根因。本轮不宣称转圈已修复。3011014 临时 NPC、完整演出及 14 份补充资源的客户端验收继续保持待完成状态。
