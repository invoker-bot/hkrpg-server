"""只读核对星魂/晋阶奖励资源及固定提交原件；不代表全部角色的客户端验收。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--reference', type=Path, default=ROOT / 'references/TurnBasedGameData')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/avatar-milestones-resources.json')
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise SystemExit('报告只能写入本工程 build 目录')
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/avatar-milestones-4.6.json').read_text('utf-8'))
    cache, hashes, blob_hashes = {}, {}, {}
    checked = 0
    for file in fixture['files']:
        path = file['path']
        local = (args.resources / path).read_bytes()
        blob = subprocess.check_output(['git', '-C', str(args.reference), 'show', fixture['commit'] + ':' + path])
        hashes[path] = hashlib.sha256(local).hexdigest()
        blob_hashes[path] = hashlib.sha256(blob).hexdigest()
        assert hashes[path] == file['sha256'], '固定资源发生变化: ' + path
        cache[path] = json.loads(local)
        original = json.loads(blob)
        # Installed JSON may use different indentation/newlines; compare all parsed data, not raw bytes.
        assert cache[path] == original, '资源与固定提交的内容不一致: ' + path
        for record in file['rows']:
            assert original[record['index']] == record['row']
            checked += 1
    avatars = cache['ExcelOutput/AvatarConfig.json']
    ranks = {row['RankID']: row for row in cache['ExcelOutput/AvatarRankConfig.json']}
    items = {row['ID']: row for file in ('ItemConfig.json', 'ItemConfigAvatarRank.json')
             for row in cache['ExcelOutput/' + file]}
    checked_ranks = 0
    for avatar in avatars:
        assert len(avatar['RankIDList']) == avatar['MaxRank']
        for level, rank_id in enumerate(avatar['RankIDList'], 1):
            rank = ranks[rank_id]
            assert rank['Rank'] == level and rank['UnlockCost']
            for cost in rank['UnlockCost']:
                item = items[cost['ItemID']]
                assert cost['ItemNum'] > 0 and item['ItemMainType'] == 'Material'
                assert item['PileLimit'] >= cost['ItemNum']
            checked_ranks += 1
    rewards = {row['RewardID']: row for row in cache['ExcelOutput/RewardData.json']}
    promotions = cache['ExcelOutput/AvatarPromotionReward.json']
    for milestone in promotions:
        assert milestone['Promotion'] > 0
        reward = rewards[milestone['PromotionRewardId']]
        entries = [(reward.get('ItemID_' + str(i), 0), reward.get('Count_' + str(i), 0)) for i in range(1, 7)]
        assert any(item and count for item, count in entries)
        for item, count in entries:
            if not item:
                assert count == 0
                continue
            assert items[item]['ItemMainType'] == 'Material' and 0 < count <= items[item]['PileLimit']
    result = dict(passed=True, clientAccepted=False, sourceCommit=fixture['commit'],
                  checkedFixtureRows=checked, avatars=len(avatars), reachableRankRows=checked_ranks,
                  unreferencedRankRows=len(ranks) - checked_ranks, promotionRewards=len(promotions),
                  sourceHashes=hashes, sourceBlobHashes=blob_hashes)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k not in ('sourceHashes', 'sourceBlobHashes')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
