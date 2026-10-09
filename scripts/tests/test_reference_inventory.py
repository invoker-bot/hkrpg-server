"""参考扫描必须记录真实注册入口，排除客户端探针和注释示例。"""
import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("reference_inventory", Path(__file__).parents[1] / "audit-references.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReferenceInventoryTest(unittest.TestCase):
    def test_go_free_functions_use_registered_command_names_and_record_the_implementation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            router = root / "gameserver/player/router.go"
            router.parent.mkdir(parents=True)
            router.write_text("""package player
var routes = map[uint16]HandlerFunc {
 cmd.GetSceneMapInfoCsReq: HanldeGetSceneMapInfoCsReq,
 cmd.InteractPropCsReq: InteractPropCsReq,
 // cmd.SwapLineupCsReq: HandleSwapLineupCsReq,
 /* cmd.FakeCsReq: FakeCsReq, */
}
""", encoding="utf-8")
            implementation = router.parent / "player_scene.go"
            implementation.write_text("""package player
func HanldeGetSceneMapInfoCsReq(g *GamePlayer, request Message) {}
func InteractPropCsReq(g *GamePlayer, request Message) {}
func UnregisteredCsReq(g *GamePlayer, request Message) {}
""", encoding="utf-8")
            result = MODULE.handler_names(root, [router, implementation])
            self.assertEqual({"GetSceneMapInfoCsReq", "InteractPropCsReq"}, set(result))
            self.assertEqual({"gameserver/player/router.go", "gameserver/player/player_scene.go"}, set(result["GetSceneMapInfoCsReq"]))

    def test_go_client_methods_and_generated_protocol_definitions_are_not_server_handlers(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = []
            for relative in ("robot/robot/game_scene.go", "protocol/proto/generated.go"):
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("func (r *Robot) ClientOnlyCsReq() {}\n", encoding="utf-8")
                paths.append(path)
            self.assertEqual({}, MODULE.handler_names(root, paths))


if __name__ == "__main__":
    unittest.main()
