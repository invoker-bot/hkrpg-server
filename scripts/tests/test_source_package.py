"""源码导出应尊重工作区删除，同时继续拒绝必需文件缺失。"""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "source_package", Path(__file__).resolve().parents[1] / "verify-source-package.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SourcePackageTest(unittest.TestCase):
    def fixture(self, root):
        subprocess.run(["git", "init", "-q"], cwd=root, check=True, capture_output=True)
        for version in ("4.2", "4.6"):
            directory = root / "src/main/resources/protocol" / version
            directory.mkdir(parents=True)
            (directory / "checksums.json").write_text("{}", encoding="utf-8")
        old = root / "src/Deleted.java"
        old.write_text("retired", encoding="utf-8")
        subprocess.run(["git", "add", "--", "src/Deleted.java"], cwd=root,
                       check=True, capture_output=True)
        old.unlink()
        (root / "src/Live.java").write_text("alive\n", encoding="utf-8")
        (root / "note with space.txt").write_text("untracked\n", encoding="utf-8")
        (root / ".gitignore").write_text("build/\nconfig.json\n", encoding="utf-8")
        (root / "config.json").write_text("operator", encoding="utf-8")

    def test_working_tree_deletion_is_excluded_and_live_untracked_files_are_delivered(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            with patch.object(MODULE, "ROOT", root), patch.object(MODULE, "REQUIRED", []), \
                    patch.object(sys, "argv", ["verify-source-package.py", "--export"]):
                MODULE.main()
            report = json.loads((root / "build/source-package-export-report.json").read_text("utf-8"))
            exported = Path(report["exportDirectory"])
            self.assertFalse((exported / "src/Deleted.java").exists())
            self.assertFalse((exported / "config.json").exists())
            self.assertEqual("alive\n", (exported / "src/Live.java").read_text("utf-8"))
            self.assertEqual("untracked\n", (exported / "note with space.txt").read_text("utf-8"))

    def test_required_file_deletion_still_rejects_delivery(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            with patch.object(MODULE, "ROOT", root), \
                    patch.object(MODULE, "REQUIRED", ["src/Deleted.java"]), \
                    patch.object(sys, "argv", ["verify-source-package.py", "--export"]):
                with self.assertRaises(SystemExit):
                    MODULE.main()
            self.assertFalse((root / "build/source-package-export-report.json").exists())


if __name__ == "__main__":
    unittest.main()
