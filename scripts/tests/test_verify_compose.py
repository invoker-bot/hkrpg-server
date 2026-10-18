import base64
import copy
import importlib.util
import json
from pathlib import Path, PureWindowsPath
from types import SimpleNamespace
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('verify_compose', ROOT / 'scripts/verify-compose.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class DispatchVoiceSwitchTest(unittest.TestCase):
    def http(self, review_mode):
        from google.protobuf.descriptor_pb2 import FileDescriptorSet
        from google.protobuf.descriptor_pool import DescriptorPool
        from google.protobuf.message_factory import GetMessageClass
        pool = DescriptorPool()
        schema = FileDescriptorSet.FromString((ROOT / 'src/main/resources/protocol/4.6/descriptor.pb').read_bytes())
        for definition in schema.file:
            pool.Add(definition)
        def get(route, raw=False):
            if route == '/health/ready':
                return {'ready': True, 'mode': 'BOTH'}
            if route.startswith('/query_dispatch'):
                response = GetMessageClass(pool.FindMessageTypeByName('Dispatch'))()
                response.region_list.add().env_type = '0' if 'CNPROD' in route else '2'
            else:
                response = GetMessageClass(pool.FindMessageTypeByName('GateServer'))(
                    ip='127.0.0.1', port=23311, ios_exam=review_mode)
            return base64.b64encode(response.SerializeToString())
        return SimpleNamespace(get=get)

    def test_review_gateway_is_rejected_even_when_healthy_and_on_correct_port(self):
        with self.assertRaisesRegex(ValueError, '审核模式'):
            verify.dispatch(self.http(True), 23311)

    def test_normal_gateway_is_verified_for_both_cn_and_os_clients(self):
        rows = verify.dispatch(self.http(False), 23311)
        self.assertEqual(['CNPRODWin4.6.0', 'OSPRODWin4.6.0'], [row['version'] for row in rows])


class ComposeVerificationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.compose = self.root / 'deploy/compose.local.yaml'
        self.compose.parent.mkdir()
        self.compose.write_text('services: {}\n')
        self.runtime = self.root / '.local/compose/runtime'
        (self.runtime / 'artifact').mkdir(parents=True)
        (self.runtime / 'work').mkdir()
        self.jar = self.runtime / 'artifact/server.jar'
        self.jar.write_bytes(b'fixture-jar')
        self.config_file = self.runtime / 'work/config.json'
        self.config_file.write_text('{}')
        self.resources = self.root / 'resources/4.6'
        self.resources.mkdir(parents=True)
        self.env = self.root / '.local/compose/compose.env'
        self.env.write_text('\n'.join([
            'HKRPG_PROJECT_NAME=hkrpg-local', 'HKRPG_REPO_DIR=' + str(self.root),
            'HKRPG_RUNTIME_DIR=' + str(self.runtime), 'HKRPG_RESOURCES_DIR=' + str(self.resources),
            'HKRPG_HTTP_PORT=18080', 'HKRPG_GAME_PORT=23311']))
        (self.runtime / 'migration.json').write_text(json.dumps({
            'runtimeSHA256': verify.sha(self.jar), 'configSHA256': verify.sha(self.config_file)}))
        self.output = self.root / 'build/check.json'
        self.target = verify.validate_inputs(self.compose, self.env, self.output, root=self.root)
        self.labels = {'io.hkrpg.managed': 'true', 'io.hkrpg.role': 'local-server',
                       'io.hkrpg.runtime-directory': str(self.runtime)}
        self.config = {'name': 'hkrpg-local', 'services': {'server': {
            'labels': self.labels, 'init': True, 'restart': 'unless-stopped',
            'read_only': True, 'tmpfs': ['/tmp'],
            'build': {'additional_contexts': {'hkrpg_resources': str(self.resources)}},
            'volumes': [dict(type='bind', source=str(self.runtime / 'work'), target='/work')],
            'ports': [dict(host_ip='127.0.0.1', protocol='tcp', published='18080', target=18080),
                      dict(host_ip='127.0.0.1', protocol='udp', published='23311', target=23311)]}}}
        self.container = {'Id': 'a' * 64, 'Config': {'Labels': self.labels | {
            'com.docker.compose.project': 'hkrpg-local', 'com.docker.compose.service': 'server',
            'com.docker.compose.project.config_files': str(self.compose)}},
            'State': {'Running': True},
            'Mounts': [dict(Type='bind', Source=str(self.runtime / 'work'), Destination='/work', RW=True)],
            'HostConfig': {'RestartPolicy': {'Name': 'unless-stopped'},
                           'ReadonlyRootfs': True, 'Tmpfs': {'/tmp': ''},
                           'PortBindings': {
                               '18080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '18080'}],
                               '23311/udp': [{'HostIp': '127.0.0.1', 'HostPort': '23311'}]}}}

    def test_matching_paths_labels_and_artifact_are_accepted(self):
        self.assertEqual(self.jar, verify.validate_compose(self.config, self.target))
        verify.validate_container(self.container, self.target)

    def test_foreign_project_env_is_rejected_before_any_docker_call(self):
        self.env.write_text(self.env.read_text().replace('hkrpg-local', 'another-app'))
        with mock.patch.object(verify, 'execute') as execute:
            with self.assertRaisesRegex(ValueError, '项目名'):
                verify.validate_inputs(self.compose, self.env, self.output, root=self.root)
            execute.assert_not_called()

    def test_runtime_path_escape_is_rejected(self):
        self.env.write_text(self.env.read_text().replace(str(self.runtime), str(self.root.parent)))
        with self.assertRaisesRegex(ValueError, '本仓库'):
            verify.validate_inputs(self.compose, self.env, self.output, root=self.root)

    def test_same_project_from_another_compose_file_is_rejected(self):
        self.container['Config']['Labels']['com.docker.compose.project.config_files'] = str(self.root / 'other.yaml')
        with self.assertRaisesRegex(ValueError, '其他Compose'):
            verify.validate_container(self.container, self.target)

    def test_a_stale_foreign_container_cannot_trigger_stop_or_up(self):
        self.container['Config']['Labels']['com.docker.compose.project'] = 'foreign-project'
        calls = []
        def command(args, **kwargs):
            calls.append([str(x) for x in args])
            if 'config' in args:
                return json.dumps(self.config)
            if 'ps' in args:
                return 'a' * 64
            if 'inspect' in args:
                return json.dumps([self.container])
            self.fail('Unexpected mutating Docker call')
        with mock.patch.object(verify, 'execute', side_effect=command):
            with self.assertRaisesRegex(ValueError, '不属于目标'):
                verify.restart(verify.Compose(self.target), None, {'containerId': 'a' * 64}, False)
        self.assertFalse(any('stop' in call or 'up' in call for call in calls))

    def test_read_only_run_never_creates_an_account_or_restarts(self):
        with mock.patch.object(verify, 'validate_inputs', return_value=self.target), \
             mock.patch.object(verify, 'Compose') as compose, \
             mock.patch.object(verify, 'dispatch', return_value=[]), \
             mock.patch.object(verify, 'java_tools', return_value=('java', 'javac')), \
             mock.patch.object(verify, 'run_probe', return_value={'passed': True}), \
             mock.patch.object(verify, 'login') as login, \
             mock.patch.object(verify, 'restart') as restart:
            compose.return_value.config.return_value = self.config
            compose.return_value.snapshot.return_value = {'runtimeSHA256': verify.sha(self.jar)}
            result = verify.run(SimpleNamespace(compose=self.compose, env_file=self.env,
                                                output=self.output, lifecycle=False))
        self.assertTrue(result['passed'])
        login.assert_not_called()
        restart.assert_not_called()
        self.assertNotIn('snapshots', result)

    def test_online_players_prevent_lifecycle_changes(self):
        http = mock.Mock()
        http.get.return_value = {'status': {'playerCount': 1}}
        with self.assertRaisesRegex(ValueError, '在线玩家'):
            verify.no_players(http)

    def test_compose_must_keep_image_read_only_and_temporary_directory_writable(self):
        for field, value in [('read_only', False), ('tmpfs', [])]:
            with self.subTest(field=field):
                config = copy.deepcopy(self.config)
                config['services']['server'][field] = value
                with self.assertRaisesRegex(ValueError, '只读镜像与/tmp'):
                    verify.validate_compose(config, self.target)

    def test_actual_container_must_keep_image_read_only_and_temporary_directory_writable(self):
        for field, value in [('ReadonlyRootfs', False), ('Tmpfs', {})]:
            with self.subTest(field=field):
                container = copy.deepcopy(self.container)
                container['HostConfig'][field] = value
                with self.assertRaisesRegex(ValueError, '只读镜像与/tmp'):
                    verify.validate_container(container, self.target)

    def test_declared_work_cannot_be_read_only(self):
        config = copy.deepcopy(self.config)
        config['services']['server']['volumes'][0]['read_only'] = True
        with self.assertRaisesRegex(ValueError, '持久化目录'):
            verify.validate_compose(config, self.target)

    def test_declared_work_cannot_be_duplicated_or_have_child_mounts(self):
        valid = self.config['services']['server']['volumes'][0]
        for extra in (valid, dict(type='volume', source='other-save', target='/work/data')):
            with self.subTest(extra=extra):
                config = copy.deepcopy(self.config)
                config['services']['server']['volumes'].append(extra)
                with self.assertRaisesRegex(ValueError, '持久化目录'):
                    verify.validate_compose(config, self.target)

    def test_actual_work_requires_unique_writable_bind_without_child_mounts(self):
        valid = self.container['Mounts'][0]
        variants = [[], [valid | {'Source': str(self.resources)}], [valid | {'RW': False}],
                    [valid | {'Type': 'volume'}], [valid, valid],
                    [valid, dict(Type='volume', Source='other-save', Destination='/work/data', RW=True)]]
        for mounts in variants:
            with self.subTest(mounts=mounts):
                container = copy.deepcopy(self.container)
                container['Mounts'] = mounts
                with self.assertRaisesRegex(ValueError, '实际持久化目录'):
                    verify.validate_container(container, self.target)

    def test_docker_desktop_bind_source_maps_only_exact_drive_and_segments(self):
        expected = PureWindowsPath('D:/Projects/Experiment/hkrpg-server/.local/runtime/work')
        source = '/run/desktop/mnt/host/d/Projects/Experiment/hkrpg-server/.local/runtime/work'
        self.assertTrue(verify.bind_source_matches(source, expected))
        self.assertFalse(verify.bind_source_matches(source.replace('/host/d/', '/host/c/'), expected))
        self.assertFalse(verify.bind_source_matches(source.replace('/host/', '/other/'), expected))
        self.assertFalse(verify.bind_source_matches(source.replace('/work', '/other/../work'), expected))
        self.assertFalse(verify.bind_source_matches(source + '/data', expected))

    def test_compose_ports_must_be_exactly_two_loopback_mappings(self):
        valid = self.config['services']['server']['ports']
        variants = [valid + [valid[0] | {'host_ip': '0.0.0.0', 'published': '28080'}],
                    valid + [valid[0]], valid[:1],
                    [valid[0] | {'host_ip': '0.0.0.0'}, valid[1]]]
        for ports in variants:
            with self.subTest(ports=ports):
                config = copy.deepcopy(self.config)
                config['services']['server']['ports'] = ports
                with self.assertRaisesRegex(ValueError, '发布端口'):
                    verify.validate_compose(config, self.target)

    def test_actual_ports_must_be_exactly_two_loopback_bindings(self):
        valid = self.container['HostConfig']['PortBindings']
        variants = [{}, valid | {'27017/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '27017'}]},
                    valid | {'18080/tcp': [{'HostIp': '0.0.0.0', 'HostPort': '18080'}]},
                    valid | {'18080/tcp': valid['18080/tcp'] * 2}]
        for bindings in variants:
            with self.subTest(bindings=bindings):
                container = copy.deepcopy(self.container)
                container['HostConfig']['PortBindings'] = bindings
                with self.assertRaisesRegex(ValueError, '实际发布端口'):
                    verify.validate_container(container, self.target)

    def test_changed_artifact_is_rejected(self):
        self.jar.write_bytes(b'changed-jar')
        with self.assertRaisesRegex(ValueError, '迁移清单'):
            verify.validate_compose(self.config, self.target)

    def test_wrong_resource_build_context_is_rejected(self):
        config = copy.deepcopy(self.config)
        config['services']['server']['build']['additional_contexts']['hkrpg_resources'] = str(self.runtime)
        with self.assertRaisesRegex(ValueError, '资源构建目录'):
            verify.validate_compose(config, self.target)

    def test_declared_mount_cannot_override_image_resources(self):
        config = copy.deepcopy(self.config)
        config['services']['server']['volumes'].append(dict(
            type='bind', source=str(self.resources), target='/resources', read_only=True))
        with self.assertRaisesRegex(ValueError, '覆盖镜像资源'):
            verify.validate_compose(config, self.target)

    def test_actual_mount_cannot_override_image_resources_or_subdirectory(self):
        for destination in ('/resources', '/resources/ExcelOutput', '/'):
            with self.subTest(destination=destination):
                container = copy.deepcopy(self.container)
                container['Mounts'] = [dict(Type='bind', Source=str(self.resources),
                                           Destination=destination, RW=False)]
                with self.assertRaisesRegex(ValueError, '覆盖镜像资源'):
                    verify.validate_container(container, self.target)


if __name__ == '__main__':
    unittest.main()
