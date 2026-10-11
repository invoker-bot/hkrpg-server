"""Fixed original seven-site bindings; server policy is separate from source shop tasks."""
import base64
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "src/test/resources/fixtures/rogue-rest-merchants-4.6.json"
PIN = "724b139d8c9c32d12552eb95745a4fee72bfe48b"


class RogueRestMerchantSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text("utf-8"))
        cls.files = {item["path"]: item for item in cls.fixture["files"]}
        cls.rows = {item["table"]: item for item in cls.fixture["rows"]}

    def file(self, suffix):
        key = next(path for path in self.files if path.endswith(suffix))
        return json.loads(base64.b64decode(self.files[key]["rawBase64"]))

    def test_full_git_blobs_and_selected_rows_have_recorded_hashes(self):
        self.assertEqual(PIN, self.fixture["sourceCommit"])
        self.assertEqual(9, len(self.files))
        self.assertEqual(12, len(self.rows))
        for item in self.files.values():
            raw = base64.b64decode(item["rawBase64"], validate=True)
            self.assertEqual(item["sha256"], hashlib.sha256(raw).hexdigest(), item["path"])
            self.assertIsInstance(json.loads(raw), dict)
        for table in self.rows.values():
            encoded = json.dumps(table["rows"], ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode()
            self.assertEqual(table["rowsSHA256"], hashlib.sha256(encoded).hexdigest(), table["table"])
            self.assertRegex(table["sourceSHA256"], r"^[0-9a-f]{64}$")

    def test_installed_resources_and_full_pinned_git_blobs_match_without_data_rewrites(self):
        reference = ROOT / "references/TurnBasedGameData"
        has_reference = (reference / ".git").exists()
        for item in self.files.values():
            raw = base64.b64decode(item["rawBase64"], validate=True)
            installed = ROOT / "resources/4.6" / item["path"]
            if installed.is_file():
                self.assertEqual(raw, installed.read_bytes().replace(b"\r\n", b"\n"), item["path"])
            if has_reference:
                original = subprocess.check_output(["git", "show", f'{PIN}:{item["path"]}'], cwd=reference)
                self.assertEqual(raw, original, item["path"])
        for table in self.rows.values():
            installed = ROOT / "resources/4.6" / table["path"]
            if installed.is_file():
                installed_rows = json.loads(installed.read_bytes())
                self.assertTrue(all(row in installed_rows for row in table["rows"]), table["table"])
            if has_reference:
                original = subprocess.check_output(["git", "show", f'{PIN}:{table["path"]}'], cwd=reference)
                self.assertEqual(table["sourceSHA256"], hashlib.sha256(original).hexdigest(), table["table"])
                self.assertTrue(all(row in json.loads(original) for row in table["rows"]), table["table"])

    def test_original_first_world_rooms_and_maps_are_not_replaced_with_dlc(self):
        rooms = {row["RogueRoomID"]: row for row in self.rows["RogueRoom"]["rows"]}
        self.assertEqual(8000102, rooms[303]["MapEntrance"])
        self.assertEqual({"34": 0}, rooms[303]["GroupWithContent"])
        self.assertEqual(8000103, rooms[306]["MapEntrance"])
        self.assertEqual({"22": 0}, rooms[306]["GroupWithContent"])
        self.assertEqual(8030301, rooms[1000012]["MapEntrance"])
        sites = self.rows["RogueMap"]["rows"]
        self.assertEqual(list(range(1, 8)), sorted(row["SiteID"] for row in sites))
        self.assertEqual({101}, {row["RogueMapID"] for row in sites})

    def test_g22_source_is_enhancement_and_g34_preserves_tutorial_condition(self):
        group = self.file("LevelGroup_P80001_F80001003_G22.json")
        npc = next(row for row in group["NPCList"] if row["ID"] == 400001)
        self.assertEqual(1013, npc["NPCID"])
        self.assertTrue(npc["Dialog"]["LevelGraph"].endswith("Act403810002.json"))
        graph = self.file("Act403810002.json")
        encoded = json.dumps(graph)
        self.assertIn("Go_Enhance", encoded)
        self.assertIn("Go_Exit", encoded)
        self.assertNotIn('"MiracleShop"', encoded)
        tutorial = self.file("LevelGroup_P80001_F80001002_G34.json")
        merchant = next(row for row in tutorial["NPCList"] if row["ID"] == 400001)
        self.assertEqual(1013, merchant["NPCID"])
        self.assertIn("403000304", json.dumps(merchant))

    def test_classic_unused_page_and_real_dlc_source_do_not_prove_first_world_ui(self):
        classic = self.file("Act403810003.json")
        self.assertIn('"MiracleShop"', json.dumps(classic))
        dlc = self.file("Act0110003.json")
        tasks = [task for sequence in dlc["OnStartSequece"] for task in sequence["TaskList"]]
        self.assertTrue(any(task.get("$type") == "RPG.GameCore.RoguePropOpenPage" and
                            task.get("Type") == "MiracleShop" for task in tasks))
        group = self.file("LevelGroup_P80201_F80201001_G35.json")
        merchant = next(row for row in group["NPCList"] if row["ID"] == 400001)
        self.assertEqual(1013, merchant["NPCID"])
        self.assertTrue(merchant["Dialog"]["LevelGraph"].endswith("Rogue130/Act0110003.json"))
        boundaries = self.fixture["boundaries"]
        self.assertTrue(boundaries["serverRecipePricing"])
        self.assertFalse(boundaries["officialPricingVerified"])
        self.assertTrue(boundaries["originalFirstWorldRoomAssignmentUnchanged"])
        self.assertFalse(boundaries["clientDialogueEntryVerified"])


if __name__ == "__main__":
    unittest.main()
