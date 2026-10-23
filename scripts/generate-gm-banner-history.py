"""从固定的 AGPL 参考模板和本机 4.6 资源生成 GM 档案；不访问网络、不修改运行数据。"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    ('LunarCore', '04b5de871c62', 'data/Banners.json'),
    ('hkrpg-go', 'ef47e49a2331', 'data/Banners.json'),
    ('HyacineDH-Core', 'dd54de89ca8df8355fae5727e49e9e2bbba55344', 'Config/GameData/Banners.json'),
    ('March7thHoney-OpenSource', 'fe00bdbda3c28ca33d01f171566aa35b5ab95083', 'Config/Custom/Banners.json'),
]
# 连续排期边界；多卡池的版本/期次另与 GachaGroupData 的 250/300/.../440 标记核对。
WINDOWS = [
    ('1.0', 2001, 2002, 2002), ('1.1', 2005, 2006, 2006),
    ('1.2', 2007, 2008, 2008), ('1.3', 2009, 2010, 2010),
    ('1.4', 2011, 2013, 2012), ('1.5', 2014, 2016, 2015),
    ('1.6', 2017, 2020, 2019), ('2.0', 2021, 2024, 2023),
    ('2.1', 2025, 2028, 2027), ('2.2', 2029, 2032, 2031),
    ('2.3', 2033, 2036, 2035), ('2.4', 2037, 2040, 2039),
    ('2.5', 2041, 2046, 2045), ('2.6', 2047, 2050, 2049),
    ('2.7', 2051, 2054, 2053), ('3.0', 2055, 2062, 2059),
    ('3.1', 2063, 2066, 2065), ('3.2', 2067, 2072, 2071),
    ('3.3', 2073, 2076, 2075), ('3.4', 2077, 2083, 2081),
    ('3.5', 2084, 2087, 2086), ('3.6', 2088, 2091, 2090),
    ('3.7', 2092, 2098, 2096), ('3.8', 2099, 2104, 2101),
    ('4.0', 2105, 2112, 2109), ('4.1', 2113, 2115, 2113),
    ('4.2', 2116, 2123, 2120), ('4.3', 2124, 2127, 2126),
    ('4.4', 2128, 2134, 2132), ('4.5', 2135, 2138, 2137),
    ('4.6', 2139, 2141, 2139),
]


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def generate():
    resources = ROOT / 'resources/4.6'
    basics = {r['GachaID']: r for r in load(resources / 'ExcelOutput/GachaBasicInfo.json')}
    items = {r['ID']: r for table in ('ItemConfigAvatar', 'ItemConfigEquipment')
             for r in load(resources / f'ExcelOutput/{table}.json')}
    text = load(resources / 'TextMap/TextMapCHS.json')
    known, sources = {}, []
    for project, commit, filename in SOURCES:
        repo = ROOT / 'references' / project
        actual = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
        if not actual.startswith(commit):
            raise RuntimeError(f'参考提交不符: {project}: {actual}')
        file = repo / filename
        sources.append({'project': project, 'commit': actual, 'path': filename, 'license': 'AGPL-3.0',
                        'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
        rows = load(file)
        if isinstance(rows, dict):
            rows = rows['Banners']
        for row in rows:
            banner_id = row.get('id', row.get('gachaId'))
            basic = basics.get(banner_id)
            if not basic or basic['GachaType'] not in ('AvatarUp', 'WeaponUp') or banner_id in known:
                continue
            up5 = row['rateUpItems5']
            if len(up5) != 1 or not basic['PrefabPath'].endswith(f'_{up5[0]}.prefab'):
                continue  # 已知上游有重复/错配 ID，不能拿错误池套用资源 ID。
            up4 = [i for i in row['rateUpItems4'] if items.get(i, {}).get('Rarity') == 'VeryRare']
            if len(up4) != 3 or len(set(up4)) != 3:
                continue
            known[banner_id] = (up5, up4, project)
    banners, names = [], {}
    for basic in basics.values():
        banner_id = basic['GachaID']
        if basic['GachaType'] not in ('AvatarUp', 'WeaponUp'):
            continue
        normalized = banner_id - 1000 if basic['GachaType'] == 'WeaponUp' else banner_id
        window = next((w for w in WINDOWS if w[1] <= normalized <= w[2]), None)
        if window is None:
            continue  # 2003/2004 为未采用的早期资源，不冒充正式历史排期。
        version, _, _, second = window
        up5 = [int(re.search(r'_(\d+)\.prefab$', basic['PrefabPath'])[1])]
        up4, source = [], 'resource-display-only'
        if banner_id in known:
            up5, up4, source = known[banner_id]
        for item_id in up5 + up4:
            names[str(item_id)] = text[str(items[item_id]['ItemName']['Hash'])]
        banners.append({'id': banner_id, 'version': version,
                        'phase': '期次待核对' if version in ('4.1', '4.6') else '下半' if normalized >= second else '上半',
                        'name': text[str(basic['PoolName']['Hash'])], 'gachaType': basic['GachaType'],
                        'rateUpItems5': up5, 'rateUpItems4': up4, 'replayable': bool(up4), 'source': source})
    indexed = {b['id']: b for b in banners}
    for group in load(resources / 'ExcelOutput/GachaGroupData.json'):
        marker = re.search(r'_(\d)(\d)0_([12])\.png$', group['PoolLabelIcon'])
        if not marker:
            continue
        expected_version = f'{marker[1]}.{marker[2]}'
        expected_phase = '上半' if marker[3] == '1' else '下半'
        for banner_id in group['GachaIDList']:
            row = indexed[banner_id]
            if (row['version'], row['phase']) != (expected_version, expected_phase):
                raise RuntimeError(f'版本/期次与原始多池分组不符: {banner_id}')
    return {'sources': sources, 'resourceCommit': load(ROOT / 'resources.lock.json')['commit'],
            'itemNames': dict(sorted(names.items(), key=lambda pair: int(pair[0]))),
            'banners': sorted(banners, key=lambda b: b['id'])}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = generate()
    file = ROOT / 'src/main/resources/gm/banner-history.json'
    content = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.check:
        assert file.read_text(encoding='utf-8') == content, 'GM 卡池档案与固定来源不符'
    else:
        file.write_text(content, encoding='utf-8')
    print(json.dumps({'banners': len(result['banners']), 'replayable': sum(b['replayable'] for b in result['banners']),
                      'versions': len({b['version'] for b in result['banners']})}))
