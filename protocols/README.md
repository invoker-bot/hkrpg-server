# 协议来源与构建边界

- `4.2/legacy.proto` 从 LunarCore lite 固定提交 `04b5de871c62895091c4d65243b2e03997e66f23` 的 Quickbuf Java 字段注释提取，保留字段号和嵌套类型，用于 Java 核心的结构转换。
- `4.6/*.proto` 来自 [March7thHoney-OpenSource](https://github.com/Mar7thLover/March7thHoney-OpenSource/tree/fe00bdbda3c28ca33d01f171566aa35b5ab95083/Proto/4.6)，固定提交 `fe00bdbda3c28ca33d01f171566aa35b5ab95083`，AGPL-3.0。原始 C# namespace 选项保留，Java 运行时不加载 C# 程序。
- `cmdid-aliases.txt` 记录同一快照的语义消息别名，不采用 Pearl 的 4.6.51 测试版 ID。
- 生成描述符位于 `src/main/resources/protocol/`，不修改 `src/generated`。当前使用 protoc 3.18.1 生成 FileDescriptorSet；运行时由 Java protobuf 4.33.1 读取。
- 上游快照保留原始字节，生成的文本统一使用 LF；Git 属性保证不同系统检出后仍可核验 SHA-256。`src/main/resources` 属于工程源码，只有项目根目录的运行数据 `resources/` 被忽略。

重新导入：

```powershell
python scripts/import-protocols.py --protoc <本机protoc的绝对路径>
python scripts/verify-protocols.py
./scripts/build.ps1
```

导入脚本拒绝不同的参考提交。修改别名或字段转换后，须更新固定字节回归和协议审计。结构映射通过仍需要客户端、资源版本和玩法语义验收。
