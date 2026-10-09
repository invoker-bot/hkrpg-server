# HKRPG Server contributor guidance

- 在 Git 追踪管理的项目中，一个功能完成并通过验证后及时 Git 提交，避免未追踪文件膨胀。
- 工作先做完再交给用户检查；只有必要抉择或明确属于以后事项时才暂停。

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
