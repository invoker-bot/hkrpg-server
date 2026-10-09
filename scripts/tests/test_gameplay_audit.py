"""资源统计不能把不在资源快照中的规则名称算作覆盖。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("gameplay_audit", Path(__file__).parents[1] / "audit-gameplay.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class GameplayAuditTest(unittest.TestCase):
    def test_empty_snapshot_never_reports_resource_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            report = MODULE.audit(Path(directory))
            self.assertEqual([], report["partiallySupportedFinishTypes"])
            self.assertEqual([], report["partiallySupportedActionTypes"])
            self.assertFalse(report["completeGameplay"])

    def test_supported_and_unknown_rules_are_counted_only_when_present(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mission = root / "Config/Level/Mission/123"
            mission.mkdir(parents=True)
            (mission / "MissionInfo_123.partial.json").write_text(json.dumps({"SubMissionList": [
                {"ID": 1231, "FinishType": "NotInFloor", "FinishActionList": [{"FinishActionType": "Recover"}]},
                {"ID": 1232, "FinishType": "FutureRule", "FinishActionList": [{"FinishActionType": "FutureAction"}]}
            ]}), encoding="utf-8")
            report = MODULE.audit(root)
            self.assertEqual(["NotInFloor"], report["partiallySupportedFinishTypes"])
            self.assertEqual(["Recover"], report["partiallySupportedActionTypes"])
            self.assertEqual({"FutureRule": 1}, report["unimplementedFinishTypes"])
            self.assertEqual({"FutureAction": 1}, report["unimplementedActionTypes"])


if __name__ == "__main__":
    unittest.main()
