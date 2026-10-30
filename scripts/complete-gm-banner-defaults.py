"""补齐新安装默认目录；现有项目默认排期保持，新增模板停用且等待首次明确启用。"""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULTS = ROOT / 'src/main/resources/defaults/Banners.json'


def complete(rows, archive):
    rows = json.loads(json.dumps(rows))
    seen = {row['id'] for row in rows}
    if len(seen) != len(rows):
        raise ValueError('Duplicate configured banner ID')
    if 4001 not in seen:
        rows.append(dict(id=4001, gachaType='Newbie', beginTime=0, endTime=0,
            rateUpItems5=[1003, 1004, 1101, 1104, 1107, 1209, 1211],
            rateUpItems4=[], disabled=True))
        seen.add(4001)
    for source in archive:
        if source['id'] in seen:
            continue
        rows.append(dict(id=source['id'], gachaType=source['gachaType'], beginTime=0, endTime=0,
            rateUpItems5=source['rateUpItems5'], rateUpItems4=source['rateUpItems4'],
            eventChance=50, disabled=True, gmTemplatePending=True))
        seen.add(source['id'])
    return rows


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    rows = json.loads(DEFAULTS.read_text('utf-8-sig'))
    archive = json.loads((ROOT / 'src/main/resources/gm/banner-history.json').read_text('utf-8'))['banners']
    updated = complete(rows, archive)
    if args.check:
        assert rows == updated, 'Missing bundled banner configurations'
    else:
        DEFAULTS.write_text(json.dumps(updated, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(dict(configured=len(updated), added=len(updated)-len(rows))))
