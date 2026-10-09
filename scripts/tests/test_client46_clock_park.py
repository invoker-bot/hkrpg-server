"""原始记录的完整读取，不代表剧情执行或游戏客户端验收。"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('clock_park', ROOT / 'scripts/recover-client-clock-park.py')
clock = importlib.util.module_from_spec(spec)
spec.loader.exec_module(clock)
FIXTURE = json.loads((ROOT / 'src/test/resources/fixtures/client46-clock-park-records.json').read_text('utf-8'))


class ClockParkOriginalRecordsTest(unittest.TestCase):
    def decoded(self, record):
        raw = bytes.fromhex(record['rawHex'])
        self.assertEqual(record['length'], len(raw))
        self.assertEqual(record['sha256'], hashlib.sha256(raw).hexdigest())
        try:
            return clock.decode(raw, record['kind'])
        except ValueError as error:
            self.fail(str(error))

    def test_all_27_configs_and_27_graphs_are_consumed(self):
        self.assertEqual(54, len(FIXTURE['records']))
        for record in FIXTURE['records']:
            with self.subTest(path=record['sourcePath']):
                value = self.decoded(record)
                self.assertEqual(record['kind'], value['$type'])
                self.assertNotIn('completed', value)

    def test_each_config_links_one_original_graph_and_keeps_omitted_defaults(self):
        records = FIXTURE['records']
        graph_paths = {r['sourcePath'] for r in records if r['kind'] == 'LevelGraphConfig'}
        self.assertEqual(27, len(graph_paths))
        for record in records[:27]:
            value = self.decoded(record)
            self.assertIn(value['LevelGraphConfigPath'], graph_paths)
        first = self.decoded(records[0])
        self.assertEqual('Boy', first['CharacterConfigs'][0]['UniqueName'])
        self.assertEqual('A', first['CharacterConfigs'][0]['Anchor'])
        self.assertFalse(first['BackgroundFrontVisible'])
        self.assertNotIn('BackgroundFrontPrefabPath', first)
        self.assertNotIn('DefaultAnimState', first['CharacterConfigs'][0])

    def test_first_graph_preserves_original_dialogue_wait_and_terminal(self):
        first = self.decoded(FIXTURE['records'][27])
        self.assertEqual([], first['OnInitSequece'][0]['TaskList'])
        tasks = first['OnStartSequece'][0]['TaskList']
        self.assertEqual(1070, tasks[0]['taskId'])
        self.assertEqual('Boy', tasks[1]['UniqueName'])
        self.assertEqual({'$type': 'ClockParkStoryAnimState', '$mask': '0x3', '$offset': 17,
                          'StartAnim': 'Loop', 'LoopAnim': 'Loop'}, tasks[1]['AnimState'])
        self.assertEqual(8589934592, tasks[2]['WaitTime']['fixedRawSigned'])
        self.assertEqual(860087061, tasks[3]['TextID']['id'])
        self.assertEqual(4367570168964178882, tasks[3]['TextID']['hash'])
        self.assertEqual('ClockParkStoryWaitPlayerClick', tasks[4]['$type'])
        self.assertEqual('FinishLevelGraph', tasks[-1]['$type'])
        self.assertNotIn('MakeOwnerEntityDie', tasks[-1])

    def test_every_truncated_record_and_trailing_byte_are_rejected(self):
        profile = clock.load_profile()
        for record in FIXTURE['records']:
            raw = bytes.fromhex(record['rawHex'])
            for end in range(len(raw)):
                with self.subTest(path=record['sourcePath'], end=end):
                    with self.assertRaises(ValueError):
                        clock.decode(raw[:end], record['kind'], profile)
            with self.assertRaises(ValueError):
                clock.decode(raw + b'\0', record['kind'], profile)

    def test_unknown_clock_task_and_extra_config_mask_are_rejected(self):
        config = bytearray.fromhex(FIXTURE['records'][0]['rawHex'])
        config[0] |= 32
        with self.assertRaises(ValueError):
            clock.decode(bytes(config), 'ClockParkStoryConfig')
        graph = bytearray.fromhex(FIXTURE['records'][27]['rawHex'])
        self.assertEqual(bytes.fromhex('ae08'), graph[7:9])
        graph[7:9] = bytes.fromhex('af09')
        with self.assertRaises(ValueError):
            clock.decode(bytes(graph), 'LevelGraphConfig')

    def test_unknown_root_kind_and_changed_profile_are_rejected(self):
        with self.assertRaises(ValueError):
            clock.decode(b'\0', 'UnknownClockParkObject')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'profile.json'
            path.write_text('{}', 'utf-8')
            with patch.object(clock, 'PROFILE', path):
                with self.assertRaises(ValueError):
                    clock.load_profile()


class ClockParkRecoveryTest(unittest.TestCase):
    def args(self, directory):
        return SimpleNamespace(client_dir=directory / 'client', destination=directory / 'resources',
                               output=directory / 'report.json')

    def fake_extract(self, args):
        profile = clock.load_profile()['clockPark']
        records = []
        for fixture in FIXTURE['records']:
            path = args.extract_dir / fixture['sourcePath']
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(bytes.fromhex(fixture['rawHex']))
            expected = {k: v for k, v in profile['records'][fixture['sourcePath']].items() if k != 'kind'}
            records.append(expected | {'sourcePath': fixture['sourcePath'], 'status': 'read',
                                       'extractedFile': str(path)})
        return {'files': profile['clientFiles'], 'indexSHA256': profile['indexSHA256'],
                'verifiedChunks': {}, 'records': records}

    def recover(self, args, extract=None):
        with patch.object(clock, 'verify_native', return_value={'fixtureOnly': True}), \
             patch.object(clock.design, 'extract', side_effect=extract or self.fake_extract):
            return clock.recover(args)

    def test_full_objects_install_once_without_changing_existing_bytes_or_mtime(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            result = self.recover(args)
            self.assertEqual(54, result['fullyConsumedRecords'])
            self.assertEqual(27, result['configGraphPairs'])
            self.assertFalse(result['unityJsonRecreated'])
            self.assertFalse(result['scriptsExecuted'])
            files = {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in Path(directory).rglob('*') if p.is_file()}
            self.assertEqual(3, len(files))
            self.assertEqual(result, self.recover(args))
            self.assertEqual(files, {p: (p.read_bytes(), p.stat().st_mtime_ns) for p in files})

    def test_conflicting_operator_resource_prevents_every_other_write(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            path = args.destination / 'ServerData/ClockParkStories.json'
            path.parent.mkdir(parents=True)
            path.write_bytes(b'operator resource')
            with self.assertRaises(FileExistsError):
                self.recover(args)
            self.assertEqual(b'operator resource', path.read_bytes())
            self.assertFalse(args.output.exists())
            self.assertEqual([path], [p for p in args.destination.rglob('*') if p.is_file()])

    def test_conflicting_report_prevents_resource_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            args.output.write_bytes(b'operator report')
            with self.assertRaises(FileExistsError):
                self.recover(args)
            self.assertEqual(b'operator report', args.output.read_bytes())
            self.assertFalse(args.destination.exists())

    def test_changed_record_metadata_or_bytes_prevents_all_outputs(self):
        for corruption in ('hash', 'bytes', 'index', 'client', 'missing', 'duplicate', 'reorder'):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as directory:
                args = self.args(Path(directory))
                def corrupt(a):
                    source = self.fake_extract(a)
                    if corruption == 'hash': source['records'][0]['sha256'] = '0' * 64
                    elif corruption == 'bytes': Path(source['records'][0]['extractedFile']).write_bytes(b'changed')
                    elif corruption == 'index': source['indexSHA256'] = '0' * 64
                    elif corruption == 'client': source['files'] = {}
                    elif corruption == 'missing': source['records'].pop()
                    elif corruption == 'duplicate': source['records'][-1] = source['records'][0]
                    elif corruption == 'reorder': source['records'].reverse()
                    return source
                with self.assertRaises(ValueError):
                    self.recover(args, corrupt)
                self.assertFalse(args.output.exists())
                self.assertFalse(args.destination.exists())

    def test_client_directory_cannot_receive_resource_or_report(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.args(Path(directory))
            for kind in ('output', 'destination'):
                changed = copy.copy(args)
                setattr(changed, kind, args.client_dir / 'forbidden')
                with self.assertRaises(ValueError):
                    self.recover(changed)


CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定本机客户端，跳过原生证据复核')
class ClockParkNativeEvidenceTest(unittest.TestCase):
    def test_actual_field_inheritance_readers_factories_and_function_bytes(self):
        result = clock.verify_native(CLIENT, clock.load_profile())
        self.assertEqual({'classes': 14, 'taskBranches': 10, 'readers': 14, 'functions': 25,
                          'registrationPoints': 20}, {k: result[k] for k in
                          ('classes', 'taskBranches', 'readers', 'functions', 'registrationPoints')})

    def test_changed_factory_number_field_layout_and_function_hash_are_rejected(self):
        for corruption in ('factory', 'field', 'function'):
            with self.subTest(corruption=corruption):
                profile = clock.load_profile()
                ext = profile['clockPark']
                if corruption == 'factory':
                    ext['tasks']['1070']['factoryMethod']['index'] += 1
                elif corruption == 'field':
                    ext['classes']['ClockParkStoryConfig']['fields'][0]['name'] = 'Unknown'
                else:
                    ext['functions']['taskFactoryInitializer']['sha256'] = '0' * 64
                with self.assertRaises(ValueError):
                    clock.verify_native(CLIENT, profile)
