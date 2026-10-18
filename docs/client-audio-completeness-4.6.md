# 4.6 中文配音不可选：完整索引补包

2026-10-19 用户确认文字已经变成中文，但配音仍为英文，语音设置无法选择。前一次安装完成的是 Sophon 中文基础分类的 173 个音频包；客户端现用 `AudioV` 实际列出 175 个中文包。缺少两个 `SubPackId=2002` 的章节包，不能以基础分类校验通过代替客户端语音完整。

## 本次修复

从官方基础资源地址补入下表两份文件及对应 `.hash` 标记；此前的 173 份中文音频、英文目录和索引不替换。所有 175 个 `.pck` 均按现用索引重新检查长度、MD5 和校验标记。

| 文件（`Chinese(PRC)/` 下） | 字节数 | MD5 | SHA256 |
| --- | ---: | --- | --- |
| `External_del_4.6_chapter5_0.pck` | 81,861,322 | `252ad846524e23056e5682609c81a8d2` | `40d4fec72327bfaa9b0ea024459ca5cfeb695c915500f8531decff2a8ce5d4f1` |
| `External_del_4.6_chapter5_1.pck` | 77,872,045 | `01e12c4ef1bcde86b06fb5e4f4a632bb` | `c40eeea75696b5487eb33d1f329fb14a12ee9f3dae95f13c67afffaa2b0830f8` |

来源为官方基础包目录：

`https://autopatchcn.bhsr.com/asb/V4.6Live/output_16566615_0c4dcd9ce0fa_5e2c76c57a3c3d/client/Windows/AudioBlock/Chinese%28PRC%29/`

下载前将官方 `AudioV` 与本机索引逐字节比较相同，下载后分别核对长度、MD5 和 SHA256。未安装新的索引或热更新版本。现用索引为 `AudioV_f4e0fbb3ae338c02662c48a07a95226c.bytes`，SHA256 为 `d32a82ee300c9daf1a0ccd988bfc11536b11608ffd0ba5a129563a0a24bb18b3`；`M_AudioV.bytes` SHA256 为 `a39cb126d5bcb722b827ea4882b24d9dcaa88fdb083cc5881a12e5a7804b4616`。

用户退出游戏后，备份 `AudioLangRedord.txt` 的原始字节，将其中 `AudioLang` 从 `English` 改为 `Chinese(PRC)`。该名称与本机 `AllowedAudioLanguageRow.WwiseLanguageKey` 一致；文字和配音的 PlayerPrefs 仍为 `cn`，本轮没有重写其他注册表设置。独立进程读回确认这些值。文件独立于下载缓存，补装和设置不依赖 Codex 会话继续运行。

## 工具与验证

新增 [音频检查与补装工具](../scripts/check-client-audio.py)，默认只读。它校验固定索引指针及内容摘要，覆盖章节包，拒绝不同版本、重复路径和现有不同内容；只在客户端停止且七项原补丁匹配时允许补装。文件先复制到临时文件，校验后以不覆盖现有目标的方式发布；复制失败会撤回本次已新增的文件。

只读复查：

```powershell
python scripts/check-client-audio.py --client-dir "C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current" --language cn --output build/audio-check.json
```

本次补装命令使用 `--source-dir build/client-language-prefs-20261019 --apply --select`，收据写入 `.local/client/chinese-language-20261019/audio-completion-receipt.json`。同目录的 `.before.json` 保存原始选择字节和精确新增文件清单，`audio-independent-readback.json` 保存独立读回结果。下载来源、摘要与回归日志保存在 `build/client-language-prefs-20261019/`，持久来源副本见 `.local/client/chinese-language-20261019/missing-audio-staged.json`。

验证结果：

- 修复前，真实客户端完整性回归失败，明确列出上述两处缺失；10 项工具行为回归通过。
- 补装后，11 项回归全部通过，包含重新读取本机全部 175 个中文包及标记、同长损坏拒绝、缺标记修复、重复运行、缓存隔离、并发目标保护和复制失败撤回。
- 安装收据确认全部 175 个包完整，七项客户端补丁 SHA256 不变；独立进程确认配音选择记录为 `Chinese(PRC)`。
- `scripts/build.ps1` 成功，7 个 Gradle 任务均为 `UP-TO-DATE`；没有重新执行全部 Java 测试。本轮没有服务端代码、Compose 或数据库变更。

## 客户端边界

文字已经由用户确认切换为中文。中文配音选项能否选择、实际开场配音是否为中文，仍需补装后重新启动客户端确认。文件完整性和选择记录不能代替播放验收。

持续加载遮罩及 3011014 临时 NPC、完整演出、14 份补充资源的真实客户端验收仍未完成。当前日志另有未处理的文字语言请求 51；它与本轮语音资源修复分开记录，尚未补处理，也不能据此断言它是加载遮罩根因。
