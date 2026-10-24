"""只读核验养成回归的固定资源，并盘点光锥/遗器经验曲线与专用叠影材料。"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/equipment-progression-resources.json')
    args = parser.parse_args()
    source = args.resources.resolve(strict=True)
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise SystemExit('报告只能写入本工程 build 目录')
    cache, hashes = {}, {}

    def read(path):
        if path not in cache:
            raw = (source / path).read_bytes()
            hashes[path] = hashlib.sha256(raw).hexdigest()
            cache[path] = json.loads(raw)
        return cache[path]

    pinned = json.loads((ROOT / 'src/test/resources/fixtures/equipment-progression-4.6.json').read_text('utf-8'))
    checked_rows = 0
    for file in pinned['files']:
        rows = read(file['path'])
        assert hashes[file['path']] == file['sha256'], '固定资源变化: ' + file['path']
        for record in file['rows']:
            assert rows[record['index']] == record['row']
            checked_rows += 1
    previous = json.loads((ROOT / 'src/test/resources/fixtures/daily-upgrade-4.6.json').read_text('utf-8'))
    for record in previous:
        path = record['source'].removeprefix('resources/4.6/')
        value = read(path)
        assert hashes[path] == record['sha256'], '原经验夹具资源变化: ' + path
        for part in record['path']:
            value = value[part]
        assert value == record['row']
        checked_rows += 1
    equipment = read('ExcelOutput/EquipmentConfig.json')
    equipment_curve = {(r['ExpType'], r.get('Level', 0)): r.get('Exp', 0) for r in read('ExcelOutput/EquipmentExpType.json')}
    promotions = read('ExcelOutput/EquipmentPromotionConfig.json')
    by_equipment = {r['EquipmentID']: r for r in equipment}
    missing = []
    for promotion in promotions:
        row = by_equipment.get(promotion['EquipmentID'])
        if row is None: continue
        for level in range(1, promotion['MaxLevel']):
            if equipment_curve.get((row['ExpType'], level), 0) <= 0:
                missing.append(['equipment', row['EquipmentID'], level])
    relics = read('ExcelOutput/RelicConfig.json')
    relic_curve = {(r['TypeID'], r.get('Level', 0)): r.get('Exp', 0) for r in read('ExcelOutput/RelicExpType.json')}
    for row in relics:
        for level in range(row['MaxLevel']):
            if relic_curve.get((row['ExpType'], level), 0) <= 0:
                missing.append(['relic', row['ID'], level])
    materials = {r['ID']: r for r in read('ExcelOutput/ItemConfig.json')}
    special = []
    for row in equipment:
        for tid in row.get('RankUpCostList', []):
            assert tid in materials and materials[tid]['ItemMainType'] == 'Material'
            special.append({'equipment': row['EquipmentID'], 'material': tid})
    assert not missing, '经验曲线不完整，需要单独修复资源: ' + str(missing[:10])
    report = dict(passed=True, clientAccepted=False, sourceCommit=pinned['sourceCommit'],
                  checkedFixtureRows=checked_rows, equipment=len(equipment), relics=len(relics),
                  specialSuperimposers=special, missingCurves=missing, sourceHashes=hashes)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in report.items() if k not in ('specialSuperimposers', 'sourceHashes')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
