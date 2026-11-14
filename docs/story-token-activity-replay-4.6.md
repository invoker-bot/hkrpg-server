# 三期千星旅伴独立复刻与实际兑换

GM 历史活动接入 3.2「千星旅伴」、3.7 第二期和 4.2 第三期。每次复刻创建独立登录天数与领奖收据，按原始 1201 奖励发放一枚 281 信物。原始等级/主任务 1000510 门槛保留，查询不登记登录；关闭与恢复沿用原 UUID，重复请求不创建新周期。

| 版本 | 活动/当前面板 | 模块 | 正式版本来源 |
| --- | --- | --- | --- |
| 3.2 | 10093 | 1009301 | [102a288d](https://github.com/DimbreathBot/TurnBasedGameData/commit/102a288d91a67afe16a84832ffd2f728a07e1ee3) |
| 3.7 | 10125 | 1012501 | [e6c196e7](https://github.com/DimbreathBot/TurnBasedGameData/commit/e6c196e760dfd4fe20c62432e36e632e67838ef8) |
| 4.2 | 10141 | 1014101 | [02b00aad](https://github.com/DimbreathBot/TurnBasedGameData/commit/02b00aadbf73627920ce07e3254f939008f5fb8e) |

版本身份由对应正式版本的 ActivityConfig/ActivityLoginConfig 确认，三期均指向 1201 → 一枚 281；不按编号顺序猜对应。`scripts/data/gm-story-token-edition-sources.json` 保存三个源提交、四张表的 URL、整表摘要及选择的行。4.2 初始快照保留 10141 的活动、模块和签到记录，当时尚无面板关联；当前固定 4.6 源明确保留 10141 面板及入口，因此不适用「缺少 4.6 面板」排除。当前来源固定为 `724b139d8c9c32d12552eb95745a4fee72bfe48b`。

领取后的信物进入原有背包，在当前 4.6 保留的原始 104 商店按一枚信物兑换一名角色。原表包含 12 个角色商品，账号角色、星魂上限和永久商店收据继续保留；领取周期独立不重置这些账号状态。三个版本的信物都是同一种 281，并非额外定义三种货币。本批原生验证在原版及两轮复刻中真正扣除九枚信物、取得九名不同的新角色，领取与实际兑换分开核对。

## 验证方法与边界

```powershell
python -m unittest scripts.tests.test_gm_activity_history scripts.tests.test_music_replay_sources
python scripts/freeze-story-token-replay-sources.py --check
python scripts/freeze-signin-gift-replay-sources.py --check
python scripts/generate-gm-activity-history.py --check
powershell -File scripts/build.ps1 -TestForks 8 -RerunTests
python scripts/smoke-server.py
python scripts/smoke-gm-signin-gifts.py --story-tokens
python scripts/smoke-gm-signin-gifts.py
```

先失败目录回归锁定三期未对应。参数化测试按原始配置完成三期原版和两轮复刻，检查不能未登录先领、日数上限、同日不推进、重复领奖拒绝、旧存档保留与序列化。共用原生脚本的 `--story-tokens` 模式使用实际 KCP 重登登记各期一日登录，不使用七日活动的受控时钟；真实发放信物、查询 104 商店、逐笔扣费和角色入库、无信物拒绝、旧页面及未来窗口拒绝、请求去重和同 UID H2 重启均独立核对。

隔离夹具仅设置 21 级及主任务 1000510，不预填信物、角色或领奖状态。测试命令和数据库读取器仅编入专用 classpath，生产 JAR 不包含它们。没有修改系统时间、原始资源、运营数据或常驻服务，没有部署或推送。服务器及 GM 网页证据不等于真实游戏客户端的活动页面、商店页面和演出验收。整个历史活动请求仍未完成。

## 2026-11-02 本批结果

完整构建 `scripts/build.ps1 -TestForks 8 -RerunTests` 通过：670 组 / 16,208 项，失败、错误、跳过均为 0，15 分 42 秒；3,259 个源码、脚本、测试、构建及原表输入前后摘要相同。9 项 Python 来源/目录回归、三份固定来源和历史档案的再生检查通过。测试命令和两个数据库读取器均未进入生产 JAR。

同产物原生验证 51 次请求、三期原版与两轮复刻共九次信物领奖、六个完整复刻实例、九枚实际扣费和九名不同角色入库通过；十次独立数据库读取、两次正常停服及一次同 UID 重启核对旧记录和角色保存。没有信物的兑换请求、未来窗口和旧页面的领奖请求均拒绝，重复领奖及 HTTP 重放不能增发信物。所有一日登记由真实 KCP 重登触发，受控日历次数为零。

共用脚本的七期签到赠礼模式在同一产物再次验证 252 次请求、75 项实际领奖和重启恢复；认证存储烟测两轮登录及账号重启恢复通过。Chrome/Playwright 在 America/La_Paz 时区验证三期的版本/名称搜索、独立编号、自定义时间字段、取消新周期、关闭/按原周期恢复，以及 1440×1000 和 390×844 的页面；无业务脚本异常或业务 HTTP 错误，既有 `/favicon.ico` 返回 404。Browser 插件不可用，真实游戏客户端画面未验收。

JAR SHA-256：`482287e409e1885dbdb4d42b150cf5b673d9c29f3b507b69ab252e4d6198043f`。完整原生记录在 `build/gm-story-token-replay-native.json`；汇总、稳定输入与日志在 `build/gm-story-token-verification/`。本轮三批累计新增 11 期，当前可复刻 42 期、排除 2 期、待补齐 164 期。
