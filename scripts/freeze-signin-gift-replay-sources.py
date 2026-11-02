"""固定七期单次赠礼/周年签到的原始面板、签到日数与奖励，不涵盖带登录奖励的完整小游戏。"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EDITIONS = {10048: '2.0-95d1415393e4', 10051: '2.1-cd17f3c9983e',
            10072: '2.5-919e37caa35e', 10085: '3.0-33c227a68a73',
            10095: '3.2-27e53cab5355', 10131: '4.0-a417ca4e57ba', 10148: '4.2-0fb1603f11e8'}


def generate():
    sources = {}
    def read(name):
        path = ROOT / 'resources/4.6/ExcelOutput' / (name + '.json')
        raw = path.read_bytes(); rows = json.loads(raw)
        sources['ExcelOutput/' + path.name] = {'sha256': hashlib.sha256(raw).hexdigest(), 'rowCount': len(rows)}
        return rows
    activities = [r for r in read('ActivityConfig') if r['ActivityID'] in EDITIONS]
    panels = [r for r in read('ActivityPanel') if r['PanelID'] in EDITIONS]
    login = [r for r in read('ActivityLoginConfig') if r['ID'] in EDITIONS]
    assert len(activities) == len(panels) == len(login) == 7
    for a in activities:
        assert a['ActivityPanelID'] == a['ActivityID'] and not a['ResidentModuleList']
        row = next(r for r in login if r['ID'] == a['ActivityID'])
        assert a['ActivityModuleIDList'] == [row['ActivityModuleID']]
        assert len(row['RewardList']) == (7 if row['ID'] in {10051, 10095, 10148} else 1)
    rewards = [r for r in read('RewardData') if r['RewardID'] in {v for r in login for v in r['RewardList']}]
    items = {v for r in rewards for k, v in r.items() if k.startswith('ItemID_') and v}
    for r in rewards:
        for name, item in [('Hcoin', 1), ('Scoin', 2), ('Exp', 22), ('Mcoin', 3)]:
            if r.get(name): items.add(item)
    return {'schemaVersion': 1, 'sourceCommit': '724b139d8c9c32d12552eb95745a4fee72bfe48b',
            'editions': {str(k): v for k, v in EDITIONS.items()}, 'sources': sources,
            'records': {'ActivityConfig': activities, 'ActivityPanel': panels,
                        'ActivityLoginConfig': login, 'RewardData': rewards,
                        'ItemConfig': [r for r in read('ItemConfig') if r['ID'] in items]}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    result = generate(); path = ROOT / 'src/main/resources/gameplay/signin-gift-replay-4.6.json'
    if args.check: assert json.loads(path.read_text('utf8')) == result, '签到赠礼原始配置已变化'
    else: path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf8')
    print(json.dumps({k: len(v) for k, v in result['records'].items()}))
