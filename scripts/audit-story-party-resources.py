"""只读盘点剧情正式角色奖励、编队动作和试用条件；新增来源必须补充回归后才通过。"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/story-party-resource-audit.json')
    args = parser.parse_args()
    source = args.resources.resolve(strict=True)
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise SystemExit('报告只能写入本工程 build 目录')
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/story-party-4.6.json').read_text('utf-8'))
    for name, digest in fixture['sourceFiles'].items():
        if hashlib.sha256((source / name).read_bytes()).hexdigest() != digest:
            raise SystemExit('固定来源变化，必须重新验证: ' + name)
    read = lambda name: json.loads((source / name).read_bytes())
    subs = {}
    for path in sorted((source / 'Config/Level/Mission').rglob('MissionInfo_*.json')):
        for sub in json.loads(path.read_bytes()).get('SubMissionList', []):
            subs.setdefault(sub['ID'], {}).update(sub)
    rewards = {r['RewardID']: r for r in read('ExcelOutput/RewardData.json')}
    avatars = {r['ID'] for r in read('ExcelOutput/ItemConfigAvatar.json')}
    maximum = max(int(key.split('_')[1]) for row in rewards.values() for key in row
                  if re.fullmatch(r'ItemID_\d+', key))
    assert maximum == 6, 'RewardExcel 需要覆盖新的奖励栏位'

    def characters(reward):
        row = rewards.get(reward, {})
        return sorted((value, row.get('Count_' + key.split('_')[1], 0))
                      for key, value in row.items() if re.fullmatch(r'ItemID_\d+', key) and value in avatars)

    payouts = []
    for main in read('ExcelOutput/MainMission.json'):
        reward = main.get('RewardID', 0)
        if characters(reward):
            payouts.append(dict(main=main['MainMissionID'], reward=reward, avatars=characters(reward)))
    for sub in subs.values():
        for reward in [sub.get('SubRewardID', 0)] + [r['RewardID'] for r in sub.get('CustomValueReward', [])]:
            if characters(reward):
                payouts.append(dict(sub=sub['ID'], reward=reward, avatars=characters(reward)))
    assert {r.get('main') for r in payouts} == {1000201, 1010801, 4030001} and len(payouts) == 3, \
        '新增正式角色奖励来源，需要补充原始资源回归'
    assert all(count == 1 for row in payouts for avatar, count in row['avatars'])
    actions = [dict(sub=sub['ID'], action=action) for sub in subs.values()
               for action in sub.get('FinishActionList', []) if 'Lineup' in action.get('FinishActionType', '')]
    assert actions == [dict(sub=100020133, action=dict(FinishActionType='ChangeLineup',
                                                     FinishActionPara=[8001, 1001, 1002, 0, 1001]))], \
        '新增或变更编队动作，需要补充完整执行和恢复回归'
    trials = [dict(sub=sub['ID'], type=sub['FinishType'],
                   scriptExists=(source / sub.get('MissionJsonPath', '-')).is_file())
              for sub in subs.values() if sub.get('FinishType') in
              {'GetTrialAvatar', 'GetTrialAvatarList', 'DelTrialAvatar', 'StoryLineAddTrialAvatar'}]
    assert len(trials) == 17 and all(row['scriptExists'] for row in trials), '试用角色来源清单变化'
    report = dict(passed=True, sourceCommit=fixture['sourceCommit'], definitions=len(subs),
                  formalAvatarMissionRewards=payouts, lineupActions=actions, trialConditions=trials,
                  maximumRewardSlot=maximum, frozenSourceFiles=fixture['sourceFiles'],
                  trialExecutionVerified=False, clientGameplayVerified=False)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, definitions=len(subs), avatarRewards=len(payouts),
                         lineupActions=len(actions), trialConditions=len(trials), output=str(output))))


if __name__ == '__main__':
    main()
