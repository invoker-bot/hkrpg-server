"""Read-only full fixed-source proof for the next finite original daily-phone candidate."""
import argparse
import base64
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description='核对3000603的50份固定整原件、17电话节点和8支路；不修改运营资源或执行客户端')
parser.add_argument('--output', type=Path, default=Path('build/daily-message-3000603-current-source-proof.json'))
parser.add_argument('--raw-blob-directory', type=Path, default=Path('build/daily-message-3000603-source-blobs'))
arguments = parser.parse_args()
OUTPUT = arguments.output.resolve()
BLOBS = arguments.raw_blob_directory.resolve()
assert OUTPUT.is_relative_to((ROOT / 'build').resolve()) and BLOBS.is_relative_to((ROOT / 'build').resolve()), '只允许写workspace/build独立证据'
assert not OUTPUT.exists(), '保留旧来源报告；请指定新的--output'
OUT = OUTPUT.parent
OUT.mkdir(parents=True, exist_ok=True)
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
SOURCE = ROOT / 'resources/4.6'
G61 = json.loads((ROOT / 'src/test/resources/fixtures/g61-npc-first-talk-4.6.json').read_text('utf-8'))
spec = importlib.util.spec_from_file_location('g61_native_source_support', ROOT / 'scripts/smoke-g61-first-talk.py')
support = importlib.util.module_from_spec(spec)
spec.loader.exec_module(support)
sha = lambda raw: hashlib.sha256(raw).hexdigest()
number = lambda value: ('json-number', Decimal(value))
objects, blobs, files = {}, {}, {}
for relative, historical in G61['sourceFiles'].items():
    original = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', PIN + ':' + relative],
                              check=True, capture_output=True).stdout
    assert sha(original) == historical['sourceBlobSHA256'], relative
    if 'sourceBlobBase64' in historical:
        assert base64.b64decode(historical['sourceBlobBase64'], validate=True) == original
    actual = SOURCE / relative
    assert actual.is_file() and actual.resolve().is_relative_to(SOURCE.resolve()), relative
    installed = actual.read_bytes()
    assert json.loads(installed, parse_int=number, parse_float=number) == json.loads(original, parse_int=number, parse_float=number), relative
    if historical['installation'] == 'present':
        assert sha(installed) == historical['sha256'], relative
    objects[relative], blobs[relative] = json.loads(original), original
    stored_blob = BLOBS / relative
    assert stored_blob.resolve().is_relative_to(BLOBS)
    stored_blob.parent.mkdir(parents=True, exist_ok=True)
    if stored_blob.exists():
        assert stored_blob.is_file() and stored_blob.read_bytes() == original, '已有原件不匹配，不覆盖：' + str(stored_blob)
    else:
        stored_blob.write_bytes(original)
    assert sha(stored_blob.read_bytes()) == sha(original)
    files[relative] = dict(sourceCommit=PIN, sourceBlobSHA256=sha(original), installedSHA256=sha(installed),
                           currentInstallation='present_matching', originalWholeObjectEqual=True,
                           historicalG61Installation=historical['installation'],
                           completeSourceBlob=str(stored_blob.relative_to(ROOT)).replace('\\', '/'))
    if relative.startswith('Config/'):
        files[relative]['sourceBlobBase64'] = base64.b64encode(original).decode('ascii')

def rows(relative):
    value = objects[relative]
    if isinstance(value, list):
        return list(enumerate(value))
    assert isinstance(value, dict)
    return list(value.items())

records = []
seen = set()

def append(kind, relative, path, row):
    key = relative, tuple(path)
    if key in seen:
        return
    seen.add(key)
    records.append(dict(kind=kind, source='resources/4.6/' + relative, sourceCommit=PIN, path=path, row=row,
                        sourceBlobSHA256=files[relative]['sourceBlobSHA256'],
                        sha256=files[relative]['installedSHA256'], installation='present',
                        originalWholeObjectEqual=True))

# Preserve original first-meeting, all three authored G61 menus, 3000602 controls, quota and source gates.
for entry in G61['records']:
    relative = entry['source'].removeprefix('resources/4.6/')
    selected = objects[relative]
    for step in entry['path']:
        selected = selected[step]
    assert selected == entry['row'], (relative, entry['path'])
    append(entry['kind'], relative, entry['path'], selected)

for name, kind, field, selected_ids in (
    ('MessageSectionConfig', 'messageSection', 'ID', {1211402}),
    ('MessageItemConfig', 'messageItem', 'SectionID', {1211402}),
    ('DialogueCondition', 'dialogueCondition', 'ID', {300060301}),
    ('DialogueNPC', 'dialogue', 'ID', {300060301}),
    ('PerformanceE', 'performance', 'PerformanceID', {300060301}),
):
    relative = 'ExcelOutput/' + name + '.json'
    for index, row in rows(relative):
        if row.get(field) in selected_ids:
            append(kind, relative, [index], row)

def one(name, field, expected):
    matches = [(index, row) for index, row in rows('ExcelOutput/' + name + '.json') if row.get(field) == expected]
    assert len(matches) == 1, (name, field, expected, len(matches))
    return matches[0][1]

main = one('MainMission', 'MainMissionID', 3000603)
daily = one('DailyMissionData', 'ID', 3000603)
quest = one('QuestData', 'QuestID', 2100003)
finish_way = one('FinishWay', 'ID', 2100003)
assert daily == dict(ID=3000603, DailyMissionType=1, GroupID=30003, UnlockMainMission=1010201,
                     IconPath='SpriteOutput/TabIcon/Quest/QuestDailyIcon.png', QuestID=2100003), daily
assert main['Type'] == 'Daily' and main['RewardID'] == 231 and main['TakeParam'] == [{'Type': 'Manual'}]
assert quest['UnlockType'] == 'FinishMission' and quest['UnlockParamList'] == [1010403]
assert finish_way['FinishType'] == 'FinishTodayDailyMissionCnt' and finish_way['Progress'] == 1
assert one('DailyMissionCount', 'DailyMissionType', 1)['DailyCount'] == 1
assert one('RewardData', 'RewardID', 231) == dict(RewardID=231, ItemID_1=2, Count_1=5000)
assert one('RewardData', 'RewardID', 103004) == dict(RewardID=103004, ItemID_1=23, Count_1=200)

header_path = 'Config/Level/Mission/3000603/MissionInfo_3000603.json'
partial_path = 'Config/Level/Mission/3000603/MissionInfo_3000603.partial.json'
header, partial = objects[header_path], objects[partial_path]
assert header == dict(MainMissionID=3000603, StartSubMissionList=[300060301], FinishSubMissionList=[300060302])
first, goal = partial['SubMissionList']
assert first['ID'] == 300060301 and first['TakeType'] == 'Auto' and first['ParamStr1'] == 'Mission_300060301'
assert goal['ID'] == 300060302 and goal['TakeParamIntList'] == [300060301]
assert goal['FinishType'] == 'MessageSectionFinish' and goal['ParamInt1'] == 1211402 and goal['IsBackTrack'] is True
assert goal['MissionJsonPath'] == 'Config/Level/Mission/3000603/Mission_300060303.json'
section = one('MessageSectionConfig', 'ID', 1211402)
group = one('MessageGroupConfig', 'ID', 11125)
assert group == dict(ID=11125, MessageContactsID=48, MessageSectionIDList=[1211400, 1211401, 1211402])
assert section == dict(ID=1211402, StartMessageItemIDList=[121140301])
nodes = {row['ID']: row for _, row in rows('ExcelOutput/MessageItemConfig.json') if row.get('SectionID') == 1211402}
assert len(nodes) == 17
paths = []

def walk(node_id, path):
    assert node_id not in path and node_id in nodes
    row = nodes[node_id]
    assert row['ItemType'] == 'Text' and row['Sender'] in {'Player', 'NPC'}
    children = row['NextItemIDList']
    assert len(children) == len(set(children))
    path = path + [node_id]
    if not children:
        replies = [item for item in path if nodes[item]['Sender'] == 'Player']
        assert len(path) == 13 and len(replies) == 3 and path[-1] == 121140315
        streamed = []
        offered = [121140301]
        prefixes = []
        reply_index = 0
        while offered:
            if len(offered) == 1 and nodes[offered[0]]['Sender'] == 'NPC':
                at = offered[0]
            else:
                prefixes.append(dict(history=list(streamed), offered=list(offered)))
                at = replies[reply_index]
                reply_index += 1
                assert at in offered
            streamed.append(at)
            offered = nodes[at]['NextItemIDList']
        prefixes.append(dict(history=list(streamed), offered=[]))
        assert [len(prefix['history']) for prefix in prefixes] == [3, 6, 9, 13]
        paths.append(dict(replies=replies, history=path, prefixes=prefixes))
    for child in children:
        walk(child, path)

walk(121140301, [])
assert len(paths) == 8 and set(nodes) == set().union(*(set(path['history']) for path in paths))

PACK = 'Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json'
menus = [row for row in objects[PACK]['OnStartSequece'][0]['TaskList']
         if row.get('Predicate', {}).get('SubMissionID') == 300060301]
assert len(menus) == 1 and menus[0]['Predicate']['SubMissionState'] == 'Started'
menu = menus[0]['SuccessTaskList'][0]
assert menu['MenuItemID'] == menu['MissionID'] == 300060301
assert menu['DialoguePath'] == 'Config/Level/Mission/3000603/Talk/Talk_300060301.json'
talk = objects[menu['DialoguePath']]
assert talk['OnStartSequece'][0]['TaskList'][0] == dict(
    **{'$type': 'RPG.GameCore.TriggerPerformance'}, PerformanceType='E', PerformanceID=300060301, ValueSource={})
act_path = 'Config/Level/Mission/3000603/Act/Act300060301.json'
task_nodes, literals = [], []

def visit(value, path=()):
    if isinstance(value, dict):
        if '$type' in value:
            task_nodes.append(dict(path=list(path), row=value))
        if any(v == '300060301_Finish' for v in value.values()):
            literals.append(dict(path=list(path), row=value))
        for key, child in value.items():
            visit(child, path + (key,))
    elif isinstance(value, list):
        for index, child in enumerate(value):
            visit(child, path + (index,))

visit(objects[act_path])
assert literals == [dict(path=['OnStartSequece', 15, 'TaskList', 1, 'CustomString'], row={'Value': '300060301_Finish'})]
assert objects[act_path]['OnStartSequece'][15]['TaskList'][1]['$type'] == 'RPG.GameCore.TriggerCustomStringOnDialogEnd'
script = objects[first['MissionJsonPath']]
assert script['OnStartSequece'][1]['TaskList'][0]['CustomString']['Value'] == '300060301_Finish'
assert script['OnStartSequece'][1]['TaskList'][-1] == {'$type': 'RPG.GameCore.FinishPerformanceMission', 'Key': 'Mission_300060301'}

runtime = {}
for name, fragments in {
    'DailyMissionAssignment.java': ['DailyMissionRepeats.supportedGraph(row.ID())'],
    'DailyMissionRepeats.java': ['sub.isBackTrack() && !monster && !message', 'boolean message = DailyMissionMessageRules.repeatable(sub)'],
    'DailyMissionMessageRules.java': ['new Definition(3000602', 'new Definition(3000603', 'static boolean declared(', 'result.remove(definition.sectionId());', 'streamedOffers()'],
    'MessageManager.java': ['DailyMissionMessageRules.declared(s.ID())', ': finished(goal.getParamInt1());', 'var result = new HashMap'],
    'GameplayDefinitions.java': ['sub.getTask().retainSourceDefinition(definition);'],
}.items():
    p = ROOT / 'src/main/java/org/hkrpg/server/gameplay' / name
    text = p.read_text('utf-8')
    runtime[name] = dict(sha256=sha(p.read_bytes()), evidence=[dict(line=i, text=line.strip())
        for i, line in enumerate(text.splitlines(), 1) if any(fragment in line for fragment in fragments)])
assert runtime['DailyMissionAssignment.java']['evidence'] and runtime['DailyMissionRepeats.java']['evidence']
license_files = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'ls-tree', '-r', '--name-only', PIN],
                               check=True, capture_output=True, text=True).stdout.splitlines()
license_matches = [name for name in license_files if Path(name).name.lower().startswith(('license', 'copying', 'notice'))]
assert not license_matches
fixture = dict(sourceCommit=PIN, sourceTitle=G61['sourceTitle'], sourceRepository=G61['sourceRepository'],
               referenceLicenseEvidence=G61['referenceLicenseEvidence'], sourceFiles=files, records=records,
               paths=paths, originalMenu=dict(source=PACK, row=menus[0]),
               actTree=dict(source=act_path, tasks=task_nodes, finishLiterals=literals),
               canonicalMissionPins={first['MissionJsonPath']: support.fingerprint(json.loads(blobs[first['MissionJsonPath']],
                   parse_int=support.NumberLiteral, parse_float=support.NumberLiteral)),
                   goal['MissionJsonPath']: support.fingerprint(json.loads(blobs[goal['MissionJsonPath']],
                   parse_int=support.NumberLiteral, parse_float=support.NumberLiteral))},
               runtimeEvidence=runtime,
               boundary=dict(readOnlyAudit=True, javaBehaviorTestsExecuted=False, nativeServerStarted=False,
                             clientScriptExecuted=False, clientUiAccepted=False, referenceRuntimeCodeImported=False,
                             licenseFilesAtFixedCommit=license_matches))
DETAILS = OUTPUT.with_name(OUTPUT.stem + '-objects.json')
assert not DETAILS.exists(), '保留旧完整对象报告'
DETAILS.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + '\n', 'utf-8')
proof = dict(passed=True, sourceCommit=PIN, originalFiles=len(files), completeObjects=len(records), paths=len(paths),
             nodes=len(nodes), repliesPerPath=3, histories=[3, 6, 9, 13], allInstalledWholeObjectsEqual=True,
             completeOriginalBlobsStored=len(files),
             candidateMain=3000603, candidateGoal=300060302, section=1211402,
             fixtureSHA256=sha(DETAILS.read_bytes()),
             boundary=fixture['boundary'])
proof['fullObjectsReport'] = str(DETAILS.relative_to(ROOT)).replace('\\', '/')
OUTPUT.write_text(json.dumps(proof, indent=2) + '\n', 'utf-8')
print(json.dumps(proof, ensure_ascii=False))
