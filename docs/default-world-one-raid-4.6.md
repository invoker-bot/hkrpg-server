# 4.6 世界1剧情副本默认难度

后续未选择入口补齐见[未选择世界1副本](unselected-world-one-raid-4.6.md)。下文记录5876项认证当时的显式Default边界及其封存验证，当前合法空偏好的新局可采用Default。

在已核对的43306/source1菜单、原始两阶段和原生字段基础上，显式选择Default(0)的新局补齐actualWorld=1/effectiveWorld=1/difficulty=0。Easy(1)仍为1/0/1；完整原始入口SHA、所属run及不可变快照继续核对，不用后来菜单选择重解释已有局。

Default事件战斗使用原始PlaneEvent20321050/WorldLevel1及Stage203210501，Level32/EliteGroup2111，战斗报文与正式玩家均为世界1。Easy仍使用203210500/Level24/世界0。原始世界1试用角色1021304、Level40/装备21002和已有技能来源保持；正式MP与临时MP隔离不变。未选择的世界1入口仍关闭，原始世界0入口不需要补造新快照；source2、其他世界/副本和正式游戏UI尚未验收。

暂停Default局后选择Easy，重入继续世界1；暂停Easy局后选择Default，重入继续世界0。资源、实际等级、run归属或快照损坏关闭继续入战/结算，不能因Default的有效等级恰好等于正式等级而忽略证明。新的合法元组只支持实际世界1，不放宽未知等级或未知难度。

新增8项Java检查最初全部因显式Default入口拒绝而失败，记录build/default-world-one-raid-red.xml；这是同一入口缺口，不当作八个独立生产缺陷。检查调用实际MissionRaid、BattleService、原始Stage及试用队伍；两种实际Player/Morphia状态各两次恢复涵盖活动Default和暂停后改选Easy。父任务、早期Talk/StageWin结果及场景到达为沿用的明确夹具。31项Easy/菜单与23项旧World0检查一并通过。

没有导入新的来源代码或新增协议。原始资源仍由docs/raid-difficulty-adjustment-4.6.md记录的五份完整Git blob、七条记录和固定提交核对。两个原始世界的Stage、世界1试用行和本机Default/Easy枚举已存在，不改写原始入口的WorldLevel或把Default改成SkipUI。

`scripts/smoke-default-entry-mission-raid.py --default-world-one`与`--easy-world-one`互斥，仍兼容无参数World0流程。两种世界1模式均要求133次KCP、11份同UID快照、两次实际原始事件战斗/QUIT、8次正常stop及7次H2重启；副本内改选拒绝，暂停时改成相反菜单值后重入。世界1、前置及早期剧情结果是明确隔离夹具，真正菜单、入口、原生入战/退出和恢复由服务端执行。没有自然战斗输入重演或实际游戏客户端UI验证。

根目录、独立源码、全部Java/Python、两种原生模式及Dispatch以build/default-world-one-verification.json为准；未完成检查不计通过。上一批Easy的5868项认证及源码快照保持封存，本次改变的是当前源码的显式Default入口。完整剧情/日常、其他宇宙和活动玩法仍未全部完成，状态保持partial。
