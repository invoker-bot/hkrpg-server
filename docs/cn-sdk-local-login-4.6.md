# 国服4.6独立副本的本地账号SDK

前批只替换了 `ClientConfig.bytes` 的 Dispatch 地址。账号界面的原生 `AccountPlatNative.dll` 仍向官方 Passport 发送登录；服务端也没有注册它实际使用的 `/account/ma-cn-passport/app/loginByPassword`。因此本地 Dispatch 可达不能说明 SDK 登录已接通。此次补丁与服务端入口分别验证，真实游戏登录仍需用户手动完成。

## 客户端数据补丁

工具为 `scripts/patch-client-cn-sdk.py`，默认只允许修改本项目 `build/` 内的独立副本，拒绝未知摘要、越界链接和已有未知改动。原安装必须另外显式选择 `--installed-client`，且仍仅限已核对的本机安装目录；本次使用该选项的用户指示与现场见末节。原 SDK 的 SHA256 固定为 `469ac7d38d15d076c71352d728354b5970a49537200ca3c2fdaa1d5b318a7844`。六组账号环境各20条完整URL共120条改为本机显式端口的HTTP入口；保留各路径及文件长度，不更改执行代码段。未支持的短信、验证码、第三方登录等路径会在本地失败，不伪造成功。

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

后续用户确认通过桌面 `StarRail.exe.lnk` 启动；直接读取快捷方式后，目标确为未修改的原安装，不能用于上述独立副本的验收。新增桌面「HKRPG 本地测试服.lnk」，目标及工作目录均固定为 `build/recover-acceptance-20261015/client`，不经过官方启动器；写入后读回核对，旧快捷方式的SHA256保持不变。证据为 `build/sdk-login-20261016/desktop-shortcut-proof.json`。测试服readiness、本地私钥、最终SDK摘要和Dispatch配置摘要重新核对通过，自动建号仍默认开启、密码仍不校验内容。按用户要求不再查看或操作游戏界面，由用户通过新入口测试并反馈；该入口修正本身不代表登录成功。

## 托管PC SDK资源配置补正

用户从新入口启动后仍反馈「账号不存在」；本地17:15:07再次收到Dispatch，尚无原生Passport登录。进一步只读核对固定DLL：`ConfigManager.LoadLocalConfig`（RVA `1bc32a90`）引用字符串槽 `977f9c8`，其完整值为 `Config/server_pc.json`；`RequestManager.LoginByAccount`（RVA `1bf9b070`）使用SDK URL配置。原生DLL补丁没有改变这份托管配置。

该TextAsset位于 `StarRail_Data/StreamingAssets/MiHoYoSDKRes/PC/mihoyo_sdk_res`。原包SHA256为 `9b34bb0ce1f91f7665e72f90b358252989c094fe7183b48e563bd1a5ec6c47b9`；完整解包得到396块与3个文件，共51,806,652字节。`server_pc`正文在展开数据偏移5,784,604，长473,014字节，原SHA256 `a58e7d005fe90a23759072974e8a424ff0393f702ba415d3b5257e770866b8fe`。16份环境配置中，国服生产环境0的 `loginUrl` 仍指向 `https://hkrpg-sdk.mihoyo.com/hkrpg_cn/mdk/shield/api/login`。

安装器现在同时预检原生DLL和已存在的固定SDK资源包。仅改环境0的11项账号URL：loginUrl、tokenVerify、comboLogin、loadConfig、comboConfig、comboConfigCenter、getAgreementInfos、comboProtocol、swConfigPC、swConfig、checkMmt；URL路径保持原值，主机改为本机入口。缩短URL留下的空间补在JSON闭引号后，保留TextAsset长度和后续对象偏移，不把NUL或空格放进URL。其他15份环境及所有其余展开字节保持原样，只有第44号压缩块需要重新压缩。独立副本最终资源包SHA256为 `8e8213ece03f10349202c20bf7011375bcd46a3b6dc06bd92ccc52c55a86e225`，原生DLL仍是上节的 `7f6842d5…`。备份、重复安装、未知改动拒绝与双文件失败回滚同时覆盖；不修改GameAssembly、metadata、原安装、系统代理或TLS逻辑。

包格式核对参考 [UnityPy的BundleFile读取实现](https://github.com/K0lb3/UnityPy/blob/master/UnityPy/files/BundleFile.py)。本工具独立实现固定格式读取与等长文本修改，未导入该项目代码或将其加入运行依赖；使用已有Python `lz4`，运行JAR不包含客户端包或本地密钥。

服务端补入国服 `loadConfig`、combo配置、协议查询和版本比较四个入口，沿用包内国服app 8 / hkrpg_cn身份。MDK账号入口继续调用原 `UsernameLoginHandler`：默认开启自动建号，以用户名查找，密码不作内容校验。同用户名改用另一密码仍得到原UID的回归通过。国服MDK日志仅增加操作名与retcode，不记录用户名、密码、请求体、令牌或响应体。

新Java回归先复现配置路径404：7项中1项纯断言失败，错误/跳过为0；补正后7项通过，既有无效登录由全量回归覆盖。Python安装回归先复现旧工具安装后loginUrl仍为官方主机，1项纯断言失败；最终10项通过，包括全部非目标字节、其他环境、原压缩块、重复安装、未知资源保护及第二文件替换失败后的恢复。RED/GREEN证据保留于 `build/sdk-login-20261016/managed-*.log` 与 `managed-routes-red.xml`，最终Python日志位于 `build/sdk-managed-login-20261016/patch-tests.log`。

新产物SHA256 `216c39babcaf63e750acdae356765649a1b0580060a2205a0e084a1773e44605`，使用独立的新配置和H2，目录 `build/sdk-managed-login-20261016/server`，HTTP18080、KCP23311、内部Mongo27079。前批配置和数据库保留。启动用忽略目录内的控制脚本保留标准输入，向同目录 `stop.request` 写入请求可经原 `stop` 命令正常退出。直接读取已修补包中的URL执行13项HTTP检查通过；它们在服务日志的17:33:19出现，属于探针流量。扩展 `scripts/smoke-cn-passport.py` 的原生与MDK链路24项、两次原stop正常退出和同账号/原令牌H2恢复通过；既有Dispatch/账号检查两轮通过。报告均在新产物目录。用户已被请从同一桌面测试入口再次登录，真实客户端登录、Gateway/KCP与3011014演出仍需单独确认。

## 按用户要求沿用原桌面入口

用户随后明确要求沿用先前桌面入口，不再从新增副本入口启动，并反馈副本入口还涉及puzzle。原 `StarRail.exe.lnk` 直接指向 `C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current\StarRail.exe`，没有启动参数。按该指示，在确认游戏已退出后，将已验证的同一数据补丁安装到这个准确目录，未修改快捷方式；读回摘要为 `66e9a857b215f9d3929544d1422846121b23b5d7d88d8bb22a0c15a2586a9eb0`。本节是前文「原安装未修改」历史现场之后的新操作，不能继续用前批边界描述当前安装。

原安装的 `AccountPlatNative.dll`、`mihoyo_sdk_res` 和 `ClientConfig.bytes` 三份原件全部先核对固定SHA再备份至 `build/sdk-login-20261016/backup/`。Dispatch补丁只替换两个固定URL及其大端长度字段，结果与已验过的独立副本相同；175字节变为149字节，摘要仍为 `8ce0cc836c12b632ab07f940c116e03a20a7b776d2f95f22366b4d839166f688`。SDK与资源包结果分别为上文 `7f6842d5…`、`8e8213ec…`。安装报告 `build/sdk-managed-login-20261016/installed-client-patch.json` 明确记录 `installedClientTarget=true`、`officialClientModified=true`、`clientLoginVerified=false`；快捷方式证据为同目录 `original-shortcut-proof.json`。未修改GameAssembly、metadata、运营配置/数据库、代理、证书、TLS验证或puzzle执行代码；登录前checkMmt URL转入既有本地风险查询接口。

安装器默认拒绝原安装，只有显式选项与固定目录同时匹配才允许；备份和报告仍必须在项目build内、客户端目录外。未知Dispatch配置在任何安装写入前拒绝，三文件共用失败恢复。最终13项Python检查通过，包括第三文件替换失败时恢复前两份文件；原安装路径的允许/拒绝用临时目录模拟，不在单元测试中修改实际安装。原安装已打补丁时，测试夹具须显式选择原件备份，仍逐份固定SHA校验，不能用补丁文件冒充原件：

```powershell
$env:HKRPG_CLIENT_ORIGINALS = (Resolve-Path build/sdk-login-20261016/backup).Path
python -m unittest scripts.tests.test_client46_cn_sdk_patch
```

已请用户通过原桌面图标重新登录；若仍出现puzzle或其他提示，只收集用户反馈，不代做puzzle，也不把本地探针当成真实客户端登录通过。

本批 `scripts/build.ps1 -RerunTests` 在15分4秒完成：524类 / 14,318项，失败、错误、跳过均为0，包含既有无效登录及新增两项回归。2,600份运行/资源/测试/构建输入前后SHA相同；最终JAR与所有本批HTTP/H2检查使用的冻结产物同为 `216c39ba…`。完整XML、输入摘要及构建证明保存于 `build/sdk-managed-login-20261016/full-xml`、`build-inputs-before.json`、`build-inputs-after.json`、`full-build-proof.json`。原入口三份安装文件和备份、公私钥配对、原GameAssembly/metadata完整SHA又经独立读回复核，见 `installed-path-proof.json`。测试服保持就绪；真实客户端通过原入口的登录结果继续等用户反馈。

## 国服 Dispatch 误选外服 SDK 环境

用户反馈原入口出现外服账号页面，并要求参考本机 LunaGC 的登录。只读对照 `D:\Projects\Experiment\LunaGC`，源码提交 `111694c7185618bf3daa0aecb2b7fb7fda6d7c98` 的 `RegionHandler` 按客户端版本前缀区分国服/外服；其 patch 子模块提交 `2b883e9dbbe7971f5763c6fd73667663c8d55072` 对 Unity HTTP 和原生 WinHTTP 分别转发。没有运行、复制该项目的 launcher/DLL，也未修改它的工作区。本次沿用本项目既有 HTTP/SDK 实现，独立修正区服响应。

本项目 `RegionInfo.toProto()` 继承的 `env_type="2"` 被用于所有客户端。这是 SDK 的外服生产环境；国服生产环境为 `0`。在修复前，对仍在运行的冻结产物 `216c39ba…` 分别发送 `CNPRODWin4.6.0`、`OSPRODWin4.6.0`，用仓库匹配的4.6 descriptor解码，两个实际HTTP响应均为 `2`，见 `build/sdk-managed-login-20261016/live-dispatch-env-red.json`。此前只修补环境0的SDK地址，无法覆盖客户端被Dispatch切换到环境2后的外服页面/账号路径。

新增 `CnDispatchRoutesTest` 在本机内存Mongo和真实HTTP上先复现：3项中2项纯断言失败，均为期望0、实际2，错误/跳过为0；原始XML及日志保存于 `dispatch-cn-red.xml/.log`。`QueryDispatchHandler` 现在在原版本校验之后选择 SDK 环境：`CNPROD`/`CNREL` 返回0；外服、未声明渠道及旧兼容调用保持2。`HttpServer` 按0/2缓存各自序列化结果，刷新时一起失效，共享同一份区服快照，不改持久化区服记录。日志仅补入环境编号，不记录请求参数或账号资料。

三个新增HTTP测试覆盖国服/外服交替请求、移动端前缀、缺省与非渠道前缀、兼容API、区服刷新及版本不匹配拒绝。自动建号、不同密码复用同一账号、原生RSA会话及旧无效登录继续由原有测试和隔离HTTP/H2检查验证。原桌面快捷方式与三份客户端数据补丁沿用上一批，不需要重新修改客户端文件。

本节修复的是服务端SDK环境选择。用户要求不再查看或操作客户端，页面结果、实际SDK登录、Gateway/KCP会话与3011014完整演出继续以用户实测及对应服务日志为准；不能用Dispatch或探针通过替代这些验收。

修复后的四类聚焦Java测试共13项通过，打包产物冻结为 `build/sdk-cn-dispatch-20261016/runtime.jar`，SHA256 `ac9ef94bafb6cf40d1cf677c29646042549e87311265ff2cc637b37a44f408a3`。同产物的原生/MDK隔离HTTP/H2检查24项、两次原stop正常退出，以及既有Dispatch/账号两轮检查通过。只读4.6 descriptor解码的现场HTTP检查11项通过，覆盖国服/外服交替、旧缺省行为和错误版本拒绝，见该目录 `native-dispatch-http.json`；它记录探针开始/结束时间，不属于游戏登录流量。

测试服升级时旧进程48052经原 `stop` 退出（1000），69,632字节H2文件和原验收配置在停服后保存至 `before-upgrade`。新产物继续使用原验收目录 `build/sdk-managed-login-20261016/server` 的配置、资源和H2，HTTP18080/KCP23311/内部Mongo27079保持原值。配置SHA256 `2972e00f778dcc923f7a912af88e6f02a53508d657219f457f1b8b08ad3a437f` 读回未变。新控制脚本和日志位于 `build/sdk-cn-dispatch-20261016`，对应正常停服请求也改为该目录的 `stop.request`；旧目录已完成的stop请求只供历史留证。

独立读回原桌面快捷方式、三份客户端数据补丁、原GameAssembly和metadata的摘要均与上一批相同；本次没有写客户端文件。证据为 `installed-path-proof.json`、`upgrade-preflight.json` 和新服务端日志。LunaGC工作区仍干净。

最终 `scripts/build.ps1` 全量525类 / 14,321项通过，失败、错误、跳过均为0，耗时16分19秒；本次test任务实际执行，编译与打包沿用前述已完成的聚焦构建产物。2,601份运行、资源、测试和构建输入前后SHA相同，最终JAR、冻结JAR及已运行验证的产物同为 `ac9ef94b…`。全部XML归档于 `build/sdk-cn-dispatch-20261016/full-xml`，完整汇总为该目录 `full-build-proof.json`。提交前测试服readiness再次通过，尚未将真实客户端登录标记为通过。
