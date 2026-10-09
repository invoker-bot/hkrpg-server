import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
catalog = json.loads((root / "src/main/resources/features.json").read_text(encoding="utf-8"))
backlog_count = len(json.loads((root / "src/main/resources/feature-backlog.json").read_text(encoding="utf-8"))["entries"])
labels = {"integrated": "源码已集成，客户端待验收", "partial": "部分实现", "placeholder": "占位响应", "planned": "待实现", "tested": "工程行为已测试"}
lines = ["# 功能覆盖与验收状态", "", f"设计目标：**{catalog['designTarget']}**；继承 Java 核心的内部协议基线：**{catalog['protocolBaseline']}**。4.6 线协议处于实验阶段，尚未取得客户端验收。", "", "此表与 `/api/features` 使用同一份目录。`integrated` 描述继承 Java 源码，不等于 4.6 完整可玩；`placeholder` 不能计为玩法完成。", "", f"固定参考实现与本地新增原生入口合计 {backlog_count} 项，见 [逐项补全清单](feature-backlog.md)，协议边界见 [4.6 设计](protocol-4.6.md)。", "", "| 功能 | 当前状态 | 证据/参考 | 已知缺口 |", "|---|---|---|---|"]
for feature in catalog["features"]:
    path = feature.get("evidence")
    if path and not (root / path).exists():
        raise RuntimeError(f"Missing evidence: {path}")
    evidence = f"[{path}](../{path})" if path else "参考 `" + feature.get("reference", "") + "`"
    lines.append(f"| {feature['name']} | {labels[feature['status']]} | {evidence} | {feature.get('gap', '')} |")
lines += ["", "## 完整功能的验收范围", "", "每一项玩法至少覆盖：进入条件、配置读取、玩家状态、正常流程、失败/退出、结算、领取防重、持久化、重连、重启和客户端通知。", "", "其他需要单独列出和验收的内容：派遣、活动签到、战令、教程、图鉴、手机/展示、故事分支、活动副本、以太战线、列车派对、学剑、Fate、组队和跨服。存在协议类型或页面响应不能证明这些内容已完成。", "", "开发先后与参考源码路径见 [架构与计划](architecture.md)。"]
(root / "docs/features.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"Validated evidence for {len(catalog['features'])} feature entries")
