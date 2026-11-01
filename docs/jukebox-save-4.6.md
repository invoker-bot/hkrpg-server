# 4.6 留声机背景音乐保存

## 原因与范围

用户在“宇宙的独白”环境音页面点击“保存背景音乐”没有效果。当前运营日志 .local/compose/work/logs/latest.log 明确记录 Unhandled 4.6.0 inbound command: 3105。此前仅转换了留声机目录查询，旧生成请求缺少可用的内部描述符，原生保存请求未进入旧播放处理器，也没有保存任何组合。

新增 Jukebox46 处理原生保存 3105/3175 和目录查询 3134/3125。保存请求的字段 13、保存响应的字段 13、目录响应的字段 12 共用 MNCBEDBDDHL：字段 1 是歌曲编号及歌曲暂停状态，字段 2 是按顺序保留的环境音编号/音量，字段 3 是环境音暂停状态。歌曲和环境音可以独立暂停；没有歌曲时可保存纯环境音，空列表可清除环境音。

Player.gameplay.jukebox 在同一玩家文档保存整个组合。保存成功后才返回新的播放容器；存储异常恢复内存中的原组合并返回失败，失败响应仍携带原容器，避免原生回调解引用空对象。没有该字段的旧存档只读使用原 currentBgm；缺省选曲读取不再触发存档写入。4.2 生成类、协议定义、运营资源及既有存档均未替换。

目录继续沿用原来的全列出策略。本次没有实现音乐物品取得及原始解锁进度，不将目录可见描述为完整留声机玩法。

## 字段与资源证据

- 固定协议定义为 protocols/4.6/CmdJukeboxType.proto、Common.proto，打包描述符保持原样。
- 通过既有 scripts/analyze-client-46.py 只读核对本机客户端，没有启动或修改客户端。MusicAlbumModule.SetCurPlayingInfo（0xd900890）调用原生请求构造 0xd99f120，后者按 0xc21（3105）发送。其类型槽 0x93ecde8 对应请求 JFFEDGDNJEA，字段常量为 13；嵌套类型槽 0x938c408、0x937a398、0x93a98f8 分别对应上述容器、歌曲和环境音。
- PlayingMusicItemsInfo 原生常量明确给出最多 5 层环境音、最大音量 100；AddPlayingWhiteNoise 拒绝超过上限和重复事件，默认音量为 100。SyncServerData（0xdbf5090）及保存回调（0xd900080）消费歌曲、环境音列表和暂停状态。提取记录位于 build/jukebox-native-types.json、jukebox-native-methods.json、jukebox-native-wire.json、jukebox-native-slots.json，属于静态字段核对，不是客户端画面或声音验收。
- 新增可选资源表 BackGroundMusicGroup.json 的读取，用原始组类型 WhiteNoise 区分歌曲和环境音；原表有 7 组，21 组是环境音。不存在于目录、类型错配、重复环境音、超量/非法音量以及错误线格式在写入前拒绝。旧资源包可缺少此可选表。

## 验证与验收边界

先增加五项行为回归，修复前五项均真实失败，记录保存在 build/jukebox-save-red.xml。最终十项新增回归和四项相邻目录回归通过，覆盖完整组合往返、纯环境音、清空列表、两个独立暂停标志、层数/音量/资源类别、错误字段、旧存档零写入、失败保存回退及重试，以及两次真实 Player/Morphia 重载。

scripts/build.ps1 在 13 分 12 秒完成，645 类 / 16,013 项测试全部通过，失败、错误、跳过均为零；完整日志为 build/jukebox-save-full-build.log。同一产物 SHA256 为 aa90b5a70cd283a0d6a1942b87faf2a1ce97e1c1b2d1fa30efec256884b33878。

独立脚本 scripts/smoke-jukebox.py 使用临时配置、账号和 H2，共 22 次原生 UDP/KCP 请求。实际保存五层组合、修改为纯环境音、清空列表、拒绝非法曲目，并在同进程重连及同 UID 正常 H2 进程重启后核对歌曲、顺序、音量和独立暂停标志；两个服务进程均经 stop 正常结束。四份原始音乐资源摘要前后保持一致。报告为 build/jukebox-save-network.json，临时运行目录为 build/smoke/jukebox-ollmb1_q。

scripts/smoke-server.py 的两轮独立 Dispatch/账号持久化检查通过，运行目录为 build/smoke/dispatch-lxnh53zc，日志为 build/jukebox-save-dispatch.log。

## 本地服务更新

正常停止 hkrpg-local-server-1 后，将当前工作目录、20,566,016 字节 H2、旧 JAR 和原部署清单备份到 build/jukebox-save-rollout-20261101。停服数据库 SHA256 为 7328e50aaead462e81c78bc5e4f1e9a825428a09dc911d0c971737c562bf1358。随后仅更新当前项目的运行产物和部署元数据；30 份运营 JSON 的摘要保持一致。

新容器健康，容器内 JAR 的摘要与上述已测试产物相同。初次附加只读检查因迁移清单仍记录旧 JAR 摘要而拒绝，原失败报告保留；同步部署摘要后，scripts/verify-compose.py 的 HTTP、SDK/网关、实际挂载及端口、UDP 心跳复查通过。最终报告为 build/jukebox-save-compose-final.json，停服备份及更新报告为 build/jukebox-save-rollout-20261101/result.json。本次部署复查没有创建生产测试账号，也没有对运营玩家发起保存请求。

全量和网络结论仅对应上述冻结产物；构建后工作区另行出现的场景组调试改动不计入本次验证与音乐修复提交。

真实游戏中点击保存、退出重开、重登后的页面与实际声音仍由用户验收；静态客户端核对及服务端测试不能代替该结论。
