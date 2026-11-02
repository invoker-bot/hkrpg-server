"""只读核对固定提交的开拓等级奖励、夹具及全部69档奖励资源。"""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/player-level-rewards-4.6.json').read_text('utf-8'))
    source = ROOT / 'resources/4.6'
    files = {}
    hashes = {}
    checked = 0
    for f in fixture['files']:
        raw = (source / f['path']).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == f['sha256'], f['path']
        original = subprocess.check_output(['git', '-C', str(ROOT / 'references/TurnBasedGameData'),
                                           'show', fixture['commit'] + ':' + f['path']])
        rows = json.loads(raw)
        assert rows == json.loads(original), f['path']
        for record in f['rows']:
            assert rows[record['index']] == record['row']
            checked += 1
        files[Path(f['path']).stem] = rows
        hashes[f['path']] = hashlib.sha256(raw).hexdigest()
    rewards = {r['RewardID']: r for r in files['RewardData'] if r}
    items = {r['ID']: r for name in ['ItemConfig', 'ItemConfigEquipment', 'ItemConfigRelic', 'ItemConfigAvatarRank']
             for r in files[name] if r}
    count = 0
    for row in files['PlayerLevelConfig']:
        if row['Level'] == 1:
            assert not row.get('LevelRewardID')
            continue
        reward = rewards[row['LevelRewardID']]
        entries = [(reward.get('ItemID_' + str(i)), reward.get('Count_' + str(i), 0)) for i in range(1, 7)]
        if reward.get('Hcoin'):
            entries.append((1, reward['Hcoin']))
        assert any(item for item, _ in entries)
        for item, amount in entries:
            if item:
                definition = items[item]
                assert 0 < amount <= definition['PileLimit']
                kind = definition['ItemMainType']
                assert kind in ['Virtual', 'Material', 'Usable', 'Equipment', 'Relic']
                if kind == 'Virtual':
                    assert item in [1, 2]
                if kind == 'Usable':
                    assert definition['ItemSubType'] == 'Food'
                if kind == 'Equipment':
                    assert any(r['EquipmentID'] == item for r in files['EquipmentConfig'] if r)
                if kind == 'Relic':
                    assert any(r['ID'] == item for r in files['RelicConfig'] if r)
        count += 1
    assert count == 69
    result = dict(passed=True, sourceCommit=fixture['commit'], fixtureRows=checked,
                  levels=count, files=hashes, clientAccepted=False)
    (ROOT / 'build/player-level-rewards-resources.json').write_text(json.dumps(result, indent=2), 'utf-8')
    print(json.dumps(dict(passed=True, fixtureRows=checked, rewardedLevels=count)))


if __name__ == '__main__':
    main()
