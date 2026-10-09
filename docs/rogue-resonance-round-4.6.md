# 共鸣round的有限标准定点数实现

Java原始定点数回调新增round和有限RoundToInt，表达式编译器允许已匹配的floor/round单参数调用。标准编码采用最近偶数规则：0.5→0、1.5→2、−0.5→0、−1.5→−2。恰好半数时检查整数奇偶，不能直接使用Math.round或转成double。

## 原生证据

固定证据 `rogue-resonance-round-4.6.json` 的规范LF SHA256为 `ec8c785b73621315ee3b1896926ca09e3ef489a954b16adfaa9dcfd9acf3ad24`。round注册键1554761690、字面量及MethodInfo指向74381／`1ce0acc0..1ce0addc`（284字节）；FixPoint.RoundToInt为68536／`1dcd1d20..1dcd1e23`（259字节）。两者与完整FixPoint静态初始化393字节合计936字节，均有PE边界、原件SHA和方法身份匹配。

ONE来自已核对的16字节成对初始化：`.rdata 556db60`的第二个8字节为标准1编码8589934592，在完整cctor写入的`9343cb8`之后形成`9343cc0`。两段舍入代码实际读取该槽；没有读取尚未执行初始化的静态槽文件内容来猜值。加一仍限定在此前核对的标准Add分支，63字节为指令片段，不冒充完整函数。

源能力中的DynamicValueRoundToInt动作不是上述公开FixPoint方法本身。对该方法直接调用点的只读定位得到三个其他模块的调用，不能据此认定共鸣动作1221已经接线。本批只实现数值回调和显式表达式入口，实际动作执行仍待查明。

## 回归与验证

17个标准数值案例和一个显式表达式调用共18个断言先真实失败。新增21项Java检查、6项Python检查覆盖正负半数、奇偶、最近量化值、4096组独立BigDecimal/Decimal HALF_EVEN交叉核对、错误函数/参数数量、函数键被伪造为数值变量和有限范围溢出。输入必须为偶数标准编码，输入及结果的整数部分绝对值小于2^30；扩展编码继续明确拒绝。此前Python floor读取器保留旧有限范围。

完整 `scripts/build.ps1` 构建通过（10分29秒），351类共8,514项Java、281项Python全量回归通过，无失败和跳过；源码包2,657个文件与151份原值协议快照核验通过。归档见 `build/rogue-resonance-round-verification.json` 和 `build/rogue-resonance-round-8514-certified.jar`。没有启动、认证、HTTP或存储改动，本批不新增网络探针。真实战斗变量、参数作用域、技能扣费顺序、任务绑定和完整共鸣效果未接入；其他13个注册回调、宇宙/剧情/活动完整玩法与客户端验收仍待完成。

```powershell
python scripts/read-rogue-resonance-round.py `
  --client-dir 'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current' `
  --output build/rogue-resonance-round-evidence.json
python -m unittest scripts.tests.test_rogue_resonance_round -v
```
