# 飞霄、遐蝶原始角色配置安装

`scripts/install-resources.py` 现在从锁定资源仓库的 Git 对象直接读取飞霄和遐蝶两份完整 LocalPlayer 配置。稀疏检出无需展开整个角色配置目录。此补充只服务于已接入 Java 的限定秘技绑定，其他角色配置不随这次修复安装。

来源为 TurnBasedGameData 提交 `724b139d8c9c32d12552eb95745a4fee72bfe48b`，原始标签 `OSPRODWin4.6.0_D16707949_A16704710_L16700845`。脚本核对 HEAD、4.6 标签和完整原件 SHA256；两份文件没有裁剪或改写。

| 原始路径 | 完整 Git blob SHA256 |
|---|---|
| `Config/ConfigCharacter/LocalPlayer/LocalPlayer_Feixiao_00_Config.json` | `cb5720a3c1f2684d32622d76b6704d883bdff83ccfecf26418948314ab0f4bd4` |
| `Config/ConfigCharacter/LocalPlayer/LocalPlayer_Castorice_00_Config.json` | `b35fc7cf48389440eccec5dca1377d0aa36861a65c36ad9de3b3f8f6f48c2caa` |

安装命令保持 `python scripts/install-resources.py`。整批来源和目标冲突检查先于写入；相同文件复用，不改修改时间，不同内容拒绝覆盖。已有资源清单保持原字节，新增清单记录补充。所有新文件使用 CREATE_NEW；目标解析到安装目录以外时拒绝安装。没有执行资源仓库内的二进制，也没有添加 references/ 运行时依赖。

实际当前运营目录先以相同固定 Git 原件补入这两份缺失文件，另建 `resource-supplement-maze-local-player-7a3a14fef096.json`。之后重复全内容哈希核对：原70,624个运营文件全部保持原内容，只增加两原件与该清单，合计70,627个文件；`config.json` 和原资源清单保持不变，未生成根目录 `data/`。证据为 `build/maze-player-config-additive-install-v1.json` 与 `build/feixiao-selected-item-blessing-console-final-operator.json`。

安装遗漏先由四项回归复现：旧脚本三项纯断言失败、一项范围控制成功。修复后四项新回归及六项原安装回归共10项通过，包含稀疏来源、第二份冲突时整批不写、原件被篡改时拒绝和重复安装不改变内容/修改时间。夹具两原件与固定 Git blob 字节及 SHA 一致；此处没有把安装数据当作完整游戏内容验收。

另用真实 Git 对象库执行隔离安装，未模拟 Git 调用：一个明确人工 Excel 控制文件与两份实际 Git 原件安装后，再次执行安装新增数为零，全部目标内容和修改时间不变。此验证没有加载人工控制表，没有启动游戏运行时，也未写运营目录。报告为 `build/maze-player-config-install-actual-git-v1.json`；RED/GREEN 和10项回归报告为 `build/maze-player-config-install-immutable-red-v1.json`、`owned-green-v1.json`、`peers-green-v1.json`。

安装成功只证明这些原件可被严格来源绑定读取。飞霄和遐蝶的完整动画、召唤物、物理交互与官方游戏客户端验收继续待完成，分别见[飞霄中断清理](maze-feixiao-abort-4.6.md)和[遐蝶退场](maze-castorice-out-4.6.md)。
