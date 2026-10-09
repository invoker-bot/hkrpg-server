# 国服 4.6 共鸣后缀表达式的有限结构读取

九条原始共鸣能力中的 346 个后缀表达式已恢复为可读树，共 32 种原始字节程序。常量保留有符号 64 位编码，变量保留有符号 32 位键；减法、除法保留原生弹栈次序。单参数调用保留调用目标和参数，没有解析函数身份或执行调用。

## 原生依据

固定 `OpCode` 类型索引为 10362，底层为 Byte；18 个值从元数据常量位置逐字节读取，不按字段顺序推测。完整执行函数 `OGACEIDNOBC.LFEEAICADIO` 为 RVA `1d23e480..1d23f8e8`，5,224 字节。函数内 18 项相对跳转表、常量/变量索引读取、栈、单参数调用和返回匹配。十份完整 PE 方法共 8,294 字节固定保存，包含先前已核对的 `DynamicFloat` 读取函数；未取得 PE 范围的短叶函数不计入完整函数证明。

当前只开放原件实际出现的九种操作码：Constant、GetGlobal、Add、Subtract、Multiply、Divide、Negative、Call、Return。其他已知操作码仍停止；Call 只接受原件的一个参数和直接 GetGlobal 目标。函数或变量键没有冒充已经确认的游戏语义。

## 边界修复与回归

能力读取器此前能接受二进制框架完整但程序非法的表达式。十四种用例实际先触发 `AssertionError: ValueError not raised`，包含空程序、未知或未开放操作码、操作数截断、常量/变量索引越界、栈下溢、未开放调用数量、缺少返回、返回后仍有字节及非唯一结果栈。补入结构校验后拒绝这些程序。

另有八项表达式回归：完整原生字节和 Byte 常量、实际跳转表、非交换运算的左右次序、调用结构、整数位宽/类型、输出边界、全部 346 个表达式，以及 32 个独立程序的每个截断前缀和附加字节。树深度最多 32、栈最多 64、程序和表最多 255 项，超出边界停止。原始能力读取仍保存原始字节，不把可读树当成安装资源。

## 复核与未完成项

```powershell
python scripts/read-rogue-resonance-postfix.py `
  --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' `
  --output build/rogue-resonance-postfix-evidence.json
python -m unittest scripts.tests.test_rogue_resonance_postfix -v
```

固定证据位于 `src/test/resources/fixtures/rogue-resonance-postfix-4.6.json`，规范 LF SHA256 为 `e18232d7dad32917737b6e475fb38a4f788de14849b3125ffb6aa5f3de409baf`。实际 DLL、元数据、完整 PE 范围和字节、18 个常量位置及九条原始记录先核对；其他客户端拒绝沿用。

这一步确认**表达式结构**，尚未确认定点数全部计算行为、战斗变量上下文、原生注册函数身份和回调生命周期，没有求值或向运营资源安装 JSON，也没有启动或修改客户端。完整回响效果、强化组合、毁灭共享镜头、其他战斗/剧情/活动及真实国服 UI 继续待完成。

## 最终验证

完整 `scripts/build.ps1` 通过 8,448 项 Java 测试、347 个测试类；全部 Python 回归 234 项通过。九条原始能力的全部 71,510 个截断前缀、九个附加字节仍通过拒绝回归；新增 32 个独立表达式的 317 个截断前缀与 32 个附加字节也全部拒绝。最终源码包核对 2,628 个文件，151 份冻结协议快照保持不变。

同一产物 SHA256 为 `da3682a2eb1d664d27e5f51c942b87daaa7c4db881de8a3b850bb3ec189143d5`，封存于 `build/rogue-resonance-postfix-8448-certified.jar`；报告为 `build/rogue-resonance-postfix-verification.json`。自有 KCP 能量探针 60 次请求／96 项检查、四处缺失菜单恢复探针 56 次请求／64 项检查通过。两套分别正常关服六次、同 UID 的 H2 正常重启五次、十二次实际 Player 读取，各自 2,254 份输入资源 SHA 保持不变。Dispatch 和账号正常重启恢复通过。

本批修复的是只读能力导出工具的非法表达式接受问题，**没有新增 Java 战斗求值器或真实游戏 UI 验收**。查找原件时的路径候选未命中、槽地址计算错误和工具命令错误不计入行为先失败证据；十四项先失败均来自完整框架非法程序的实际断言。
