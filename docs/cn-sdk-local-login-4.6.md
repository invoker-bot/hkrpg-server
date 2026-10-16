# 国服4.6独立副本的本地账号SDK

前批只替换了 `ClientConfig.bytes` 的 Dispatch 地址。账号界面的原生 `AccountPlatNative.dll` 仍向官方 Passport 发送登录；服务端也没有注册它实际使用的 `/account/ma-cn-passport/app/loginByPassword`。因此本地 Dispatch 可达不能说明 SDK 登录已接通。此次补丁与服务端入口分别验证，真实游戏登录仍需用户手动完成。

## 客户端数据补丁

工具为 `scripts/patch-client-cn-sdk.py`，只允许修改本项目 `build/` 内的独立副本，拒绝原安装、未知摘要、越界链接和已有未知改动。原 SDK 的 SHA256 固定为 `469ac7d38d15d076c71352d728354b5970a49537200ca3c2fdaa1d5b318a7844`。六组账号环境各20条完整URL共120条改为本机显式端口的HTTP入口；保留各路径及文件长度，不更改执行代码段。未支持的短信、验证码、第三方登录等路径会在本地失败，不伪造成功。

只替换 `0x6c9480` 的268字节1024位登录公钥槽；另一份2048位公钥保持原字节。公钥槽原SHA256为 `42a8cfbff43e26979202d5bc16d17602c627ac40d54dc43b48b1815e75d5daac`。测试密钥在忽略的隔离目录中独立生成，私钥不进入Git或报告。备份保留完整原SDK，安装先检查全部内容，再以文件替换方式写入；重复安装同一公钥和地址不再改写。需要先完全退出这个副本。

当前副本是 `build/recover-acceptance-20261015/client/StarRail.exe`，原安装仍在 `C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current`。最终SDK补丁摘要为 `7f6842d5251687b5f5c67df7b89192193269d227e4c476de8c47ac072acc7797`，对应安装报告为 `build/sdk-login-20261016/client-patch-fixed-length.json`。没有更改系统代理、证书或客户端TLS验证逻辑，也没有运行参考仓库的launcher/DLL。

首版补丁摘要 `d61a42bda50ecd5e47b8070826816145486937f8dd4ba4ec74044059b6932e2f` 用NUL补齐缩短的URL，虽然文件长度不变，但原生构造器按原URL长度创建字符串：例如RVA `106477` 传入 `0x4a`，`106486` 调用 `137f20`。因此补齐的NUL会进入C++字符串，首版不能作为最终补丁。新增回归在120个URL子用例全部失败后修正：仅在本机URL的userinfo中补入无凭据含义的 `p` 字符与 `@`，保持完整URL长度、唯一末尾NUL、HTTP路径和查询。安装器只接受原件、同钥同地址的最终补丁或逐字节重建的旧NUL补丁，其他修改仍拒绝。该缺陷已经证实；是否是本次界面错误的唯一原因仍需真实客户端复测。

```powershell
python scripts/patch-client-cn-sdk.py --client-dir build/recover-acceptance-20261015/client --server http://127.0.0.1:18080 --public-key build/sdk-login-20261016/keys/passport-public.pem --backup-dir build/sdk-login-20261016/backup --output build/sdk-login-20261016/client-patch-repeat.json
```

工具需要现有Python环境中的 `cryptography`、`pefile`。上述公钥必须与对应测试服配置中的私钥配对；密钥丢失后需要生成新配对并重新安装副本补丁，不能使用另一份公钥覆盖为“相同补丁”。

## 服务端入口

`CnPassportRoutes` 注册三个原生路径，保留原有 `hkrpg_cn` 和海外SDK兼容入口。

| 路径 | 行为 |
|---|---|
| `/account/ma-cn-passport/app/loginByPassword` | 用显式配置的本地RSA私钥按PKCS1填充解密账号、密码；以实际账号名查找或按既有开关创建账号，保存后返回令牌 |
| `/account/ma-cn-session/app/verify` | 按 `mid` 与嵌套token共同核对已保存账号，拒绝跨账号令牌 |
| `/account/ma-cn-session/app/exchange` | 仅处理已核对的1→4类型交换，先验证实际账号/token，再返回可供既有combo入口校验的令牌 |

新增配置 `loginOptions.cnPassportPrivateKeyPath` 指向本地PKCS8 DER文件。默认空字符串，不自动生成默认私钥，也不读取官方凭据。缺钥、错误密文、无效UTF-8、空账号、错误结构和未知token类型返回错误；关闭自动建号时保留现有拒绝行为。响应中的aid/mid来自实际账号，不使用旧版 `AppLoginHandler` 的固定 `player` 方式。日志只记录操作名和retcode，不记录账号、密码、密文、请求体或token。

密码仍遵循本服既有“不校验密码”的本地账号约定；解密只用于读取原生SDK请求与区分实际账号，并不构成正式密码认证。旧兼容AppLogin入口保持上游固定账号行为。完整SDK配置、其他Passport操作、官方身份认证及真实游戏会话均不能据此声明完成。

## 验证与现场

五项新Java回归先在原实现全部出现纯断言失败，均为原生密码路径404，错误/跳过为0，见 `build/sdk-login-20261016/red.xml`。修复后的五项及既有无效登录一项通过。最终六项Python检查通过，逐一恢复允许变化的URL和公钥区域后，整份SDK必须与原件完全相等；覆盖本机地址限制、SDK摘要/密钥长度、固定长度字符串、旧补丁升级、重复安装、未知改动保护及原安装拒绝。另从最终SDK取出原生长度的登录URL交给本机curl，HTTP 200且无效请求返回-202，见 `fixed-length-url-http.json`；该探针不是游戏流量。

`scripts/build.ps1 -RerunTests` 全量524类 / 14,316项通过，失败、错误、跳过均为0，耗时16分15秒。构建前后2,600份运行、资源、测试及构建输入摘要相同，产物与冻结检查JAR相同，见 `full-build.log`、`full-test-totals.json`、`build-input-proof.json`。前批74项相关Python检查与首版5项共79项通过；最终补丁的6项另行重跑通过，不将中止的全目录Python发现计为成功。

打包运行产物SHA256为 `2110e966eec9f1d014c4d77ffcc182d8f42f0c1bca72619f4eb9ad6728235efd`。该产物的独立Dispatch/旧账号检查两轮通过；实际游戏验收服务上的原生RSA路径10项HTTP检查通过，报告为 `passport-http-proof.json`。新增 `scripts/smoke-cn-passport.py` 对同一产物执行14项HTTP/H2检查，两次原 `stop` 正常退出、同账号和原令牌重启恢复、账号隔离、交换及combo通过，见 `passport-h2.json`。首轮工具误将上游 `stop` 的退出码1000断言为0，失败日志保留于 `passport-h2.log`；修正工具后另起全新隔离目录通过，未将首轮记作成功。

```powershell
python -m unittest scripts.tests.test_client46_cn_sdk_patch
python scripts/smoke-cn-passport.py --jar build/sdk-login-20261016/runtime.jar --output build/sdk-login-20261016/passport-h2-repeat.json
```

新现场使用全新配置与H2：HTTP 18080、KCP 23311、内部数据库27078，目录为 `build/sdk-login-20261016/server`；前批验收数据库保留。首版副本在16:46:09实际请求本地Dispatch；用户随后反馈仍为账号密码错误，服务日志没有该次本地Passport请求，不能说已完成登录。之后用户按Esc停止界面操作，本轮不再控制客户端。最终等长补丁在客户端退出后安装，尚未重新启动或登录。上述网络探针不操作游戏登录界面，不包含真实客户端SDK登录、Gateway/KCP登录、3011014完整演出或中途重入。
