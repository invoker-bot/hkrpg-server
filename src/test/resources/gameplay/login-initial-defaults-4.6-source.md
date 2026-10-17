# 登录初始化默认引用样本

只保留本机 4.6 资源表中用于登录回归的四条完整记录：`TrainPartyProgress` 的回合 1、`GridFightDivisionInfo` 的分区 1，以及 `GridFightConstCommon` 的赛季和子赛季常量。没有导入运行库；生产代码也不读取本测试文件。

来源文件路径及完整 SHA256 保存在同目录 JSON 的 `sources` 中。记录从这些原表直接筛选，未补写原表省略的 `DivisionLevel` 或 `UnlcokRequireArea` 零值。客户端对字段的解析和实际消费路径另见 `docs/login-client-initial-errors-4.6.md`，不是按 `unlock_area_num` 的名字推断回合含义。

本夹具让普通 Java 回归在没有本机资源目录、游戏客户端或参考仓库的机器上也能运行。原表相等核对、实际资源加载和 H2/KCP 重启检查保留为本机独立验证，不能用样本回归代替实际客户端进图验收。
