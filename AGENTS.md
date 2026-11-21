# HKRPG Server contributor guidance

- 在 Git 追踪管理的项目中，一个功能完成并通过验证后及时 Git 提交，避免未追踪文件膨胀。
- 工作先做完再交给用户检查；只有必要抉择或明确属于以后事项时才暂停。
- 用户提出问题后，定位根因，并在全局剧情、任务、交互和相关场景中举一反三排查相同根因或机制的类似问题；对确认存在的问题先补充有意义的回归测试，再一并修复和验证。保留原剧情条件及玩家进度，不凭缺失证据推定完成或奖励，并如实记录排查范围和尚未验证之处。
- 未经用户同意，不使用 computer use。
- 新增或修复交互、任务、协议及场景加载时，给关键拒绝和意外跳过留下可定位的诊断。复用限频和重复计数，只记录必要的编号与状态，详见 [客户端与服务端日志](docs/game-diagnostics.md)。
- `README.md` 集中说明项目定位、功能范围、启动部署方式和文档入口。具体修复、验证与部署记录统一写入 `docs/` 下对应的专题文档。

- Read README.md, docs/features.md and docs/architecture.md before changing gameplay.
- The current protocol baseline is LunarCore lite 4.2.0. Do not claim a new client version by changing GameConstants.VERSION alone.
- Runtime code is Java 21. Reference sources stay under ignored references/ and never become runtime dependencies automatically.
- Preserve LunarCore provenance and AGPL license. Additional imported code needs a known compatible license and a recorded source commit.
- Keep existing emu.lunarcore package names while reflection and plugin registration depend on them. Project bootstrap belongs in org.hkrpg.server.
- Use meaningful failing regressions for behavior fixes, then run scripts/build.ps1 (Windows) or ./gradlew test shadowJar. Run scripts/smoke-server.py after auth, storage, bootstrap or HTTP changes.
- Keep functionality status honest: a handler, fixed completion or empty response is not a complete gameplay feature. Update src/main/resources/features.json and run scripts/generate-feature-docs.py when coverage changes.
- Do not modify src/generated protocol classes by hand. A protocol update needs matched field numbers, command IDs and resource versions, plus client acceptance.
- Do not overwrite operator data, config.json, database files or existing resources when installing defaults.
- Do not run binaries or launchers downloaded inside reference repositories merely to inspect their source.
- Keep workflow documentation in Chinese and describe exact verification boundaries.
