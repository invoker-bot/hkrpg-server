import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('visual_catalog',Path(__file__).resolve().parents[1]/'generate-client-visual-groups.py')
catalog=importlib.util.module_from_spec(spec);spec.loader.exec_module(catalog)


class ClientVisualCatalogTest(unittest.TestCase):
    def test_fingerprint_preserves_numeric_lexemes(self):
        self.assertNotEqual(catalog.fingerprint('{"x":1.0}'),catalog.fingerprint('{"x":1}'))
        self.assertEqual(catalog.fingerprint('{"y":2,"x":1e-05}'),catalog.fingerprint('{"x":1e-05,"y":2}'))

    def test_changed_native_ownership_or_placement_is_rejected(self):
        source={'PropList':[{'ID':300001,'PropID':100}]}
        self.assertEqual([],catalog.binding_differences(source,source))
        other={'LoadSide':'Server','PropList':[{'ID':300001,'PropID':101}]}
        self.assertEqual(['LoadSide','PropList'],catalog.binding_differences(source,other))

    def test_nested_server_requests_prevent_local_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'Config/Level').mkdir(parents=True)
            (root/'Config/Level/a.json').write_text('{"LevelGraphPath":"Config/Level/b.json"}')
            (root/'Config/Level/b.json').write_text('{"$type":"RPG.GameCore.PropReqInteract"}')
            self.assertEqual('server-prop-request',catalog.graph_reason(root,'Config/Level/a.json',{}))

    def test_catalog_encoding_is_deterministic_and_readable(self):
        doc=dict(schema=1,indexSHA256='pinned',groups=[[1,2,3,False,'hash']])
        self.assertEqual(doc,json.loads(catalog.encode(doc)))
        self.assertEqual(catalog.encode(doc),catalog.encode(doc))


if __name__=='__main__':unittest.main()
