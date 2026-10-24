"""只读盘点突破连续性、行迹依赖与费用；校验回归夹具的原始来源。"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/progression-gates-resources.json')
    args = parser.parse_args()
    hashes, cache = {}, {}

    def read(name):
        path = 'ExcelOutput/' + name
        if path not in cache:
            raw = (ROOT / 'resources/4.6' / path).read_bytes()
            hashes[path] = hashlib.sha256(raw).hexdigest()
            cache[path] = json.loads(raw)
        return cache[path]

    fixture = json.loads((ROOT / 'src/test/resources/fixtures/progression-gates-4.6.json').read_text('utf-8'))
    count = 0
    for file in fixture['files']:
        rows = read(Path(file['path']).name)
        assert hashes[file['path']] == file['sha256']
        for record in file['rows']:
            assert rows[record['index']] == record['row']
            count += 1
    missing = []
    totals = {}
    for name, key in [('AvatarPromotionConfig.json', 'AvatarID'), ('EquipmentPromotionConfig.json', 'EquipmentID')]:
        rows = read(name)
        indexed = {(r[key], r.get('Promotion', 0)): r for r in rows}
        totals[name] = len(rows)
        for (tid, stage), row in indexed.items():
            if stage == 6:
                continue
            next_row = indexed.get((tid, stage + 1))
            if not next_row or next_row['MaxLevel'] <= row['MaxLevel']:
                missing.append([name, tid, stage])
    traces = read('AvatarSkillTreeConfig.json') + read('AvatarSkillTreeConfigLD.json')
    points = {(r['AvatarID'], r.get('EnhancedID', 0), r['PointID']) for r in traces if r['Level'] == 1}
    for row in traces:
        for prior in row['PrePoint']:
            if (row['AvatarID'], row.get('EnhancedID', 0), prior) not in points:
                missing.append(['trace-prerequisite', row['PointID'], prior])
    materials = {r['ID']: r for r in read('ItemConfig.json')}
    for row in traces + read('AvatarPromotionConfig.json') + read('EquipmentPromotionConfig.json'):
        for item in row.get('MaterialList', row.get('PromotionCostList', [])):
            tid = item['ItemID']
            if item['ItemNum'] <= 0 or (tid != 2 and (tid not in materials or materials[tid]['ItemMainType'] != 'Material')):
                missing.append(['cost', tid])
    result = dict(passed=not missing, clientAccepted=False, sourceCommit=fixture['sourceCommit'],
                  fixtureRows=count, promotionRows=totals, traceRows=len(traces), missing=missing, sourceHashes=hashes)
    output = args.output.resolve()
    assert output.is_relative_to(ROOT / 'build'), '报告只能写入 build'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'sourceHashes'}, ensure_ascii=False))
    assert result['passed'], '配置缺口需要复核，不能称全覆盖'


if __name__ == '__main__':
    main()
