"""离线生成 GM 卡池档案：固定的公开公告事实 + 本机 4.6 资源；不修改运行配置。"""
import argparse
from datetime import date, time
import hashlib
import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'scripts/data/gm-banner-history-web.json'
UNUSED = {2003, 2004, 3003, 3004}


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def clean_name(value):
    return html.unescape(re.sub(r'<[^>]+>', '', value))


def phase_label(row, rows):
    same_version = [r for r in rows if r['version'] == row['version']]
    first = min(r['startDate'] for r in same_version)
    last = max(r['endDate'] for r in same_version)
    if row['startDate'] == first and row['endDate'] == last:
        return '全期'
    if row['phaseCount'] == 3:
        return ('第一期', '第二期', '第三期')[row['phaseIndex'] - 1]
    return '上半' if row['phaseIndex'] == 1 else '下半'


def generate(evidence=None):
    evidence = load(EVIDENCE) if evidence is None else evidence
    if evidence['schemaVersion'] != 1:
        raise ValueError('未知公告事实格式')
    resources = ROOT / 'resources/4.6'
    basics = {r['GachaID']: r for r in load(resources / 'ExcelOutput/GachaBasicInfo.json')}
    items = {r['ID']: r for table in ('ItemConfigAvatar', 'ItemConfigEquipment')
             for r in load(resources / f'ExcelOutput/{table}.json')}
    text = load(resources / 'TextMap/TextMapCHS.json')
    notices = {r['url']: r for r in evidence['notices']}
    if len(notices) != len(evidence['notices']):
        raise ValueError('公告来源重复')
    for notice in notices.values():
        if (not re.fullmatch(r'https://www\.hoyolab\.com/article/[0-9]+', notice['url'])
                or str(notice['authorUid']) != '172534910'
                or not re.fullmatch(r'[0-9a-f]{64}', notice['textSHA256'])):
            raise ValueError('缺少可核对的官方公告身份或快照')
    rows = evidence['banners']
    releases = {r['version']: r for r in evidence['versionSources']}
    if set(releases) != {r['version'] for r in rows}:
        raise ValueError('版本维护公告目录不完整')
    for version, release in releases.items():
        if (not release['sourceUrl'].startswith('https://hsr.hoyoverse.com/en-us/news/')
                or release['startDate'] != min(r['startDate'] for r in rows if r['version'] == version)):
            raise ValueError(f'版本维护日期不符: {version}')
    expected_ids = {r['GachaID'] for r in basics.values()
                    if r['GachaType'] in ('AvatarUp', 'WeaponUp')} - UNUSED
    if len({r['id'] for r in rows}) != len(rows) or {r['id'] for r in rows} != expected_ids:
        raise ValueError('公告档案必须与全部受支持卡池资源逐项对应，不能遗漏或重复')
    banners, names = [], {}
    for row in rows:
        banner_id = row['id']
        basic = basics[banner_id]
        up5, up4 = row['rateUpItems5'], row['rateUpItems4']
        if (row['gachaType'] != basic['GachaType'] or len(up5) != 1
                or len(up4) != 3 or len(set(up4)) != 3 or set(up5) & set(up4)
                or not basic['PrefabPath'].endswith(f'_{up5[0]}.prefab')):
            raise ValueError(f'卡池类型、五星展示或四星 UP 不符: {banner_id}')
        kind = 'AvatarCard' if row['gachaType'] == 'AvatarUp' else 'Equipment'
        for item_id in up5 + up4:
            rarity = 'SuperRare' if item_id in up5 else 'VeryRare'
            if (item_id not in items or items[item_id]['Rarity'] != rarity
                    or items[item_id]['ItemMainType'] != kind):
                raise ValueError(f'UP 星级或类型不符: {banner_id}/{item_id}')
            names[str(item_id)] = clean_name(text[str(items[item_id]['ItemName']['Hash'])])
        if (row['sourceUrl'] not in notices or row['verification'] not in
                ('official_text', 'official_text_and_image')):
            raise ValueError(f'卡池缺少已核对的官方公告: {banner_id}')
        if row['verification'] == 'official_text_and_image' and not notices[row['sourceUrl']].get('imageRosters'):
            raise ValueError(f'缺少公告图片来源: {banner_id}')
        if (not re.fullmatch(r'[0-9]+\.[0-9]+', row['version']) or row['phaseCount'] not in (2, 3)
                or not 1 <= row['phaseIndex'] <= row['phaseCount']
                or row['phase'] != phase_label(row, rows)):
            raise ValueError(f'版本、期次或全期窗口不符: {banner_id}')
        if date.fromisoformat(row['startDate']) > date.fromisoformat(row['endDate']):
            raise ValueError(f'历史时间倒置: {banner_id}')
        if row['serverTimeZone'] != '+08:00' or type(row['startAfterUpdate']) is not bool:
            raise ValueError(f'历史时区或更新后标记不符: {banner_id}')
        start = row['startDate'] + (' 更新后' if row['startAfterUpdate'] else
                                    ' ' + time.fromisoformat(row['startTime']).isoformat())
        end = row['endDate'] + ' ' + time.fromisoformat(row['endTime']).isoformat()
        banners.append({'id': banner_id, 'version': row['version'], 'phase': row['phase'],
                        'phaseIndex': row['phaseIndex'], 'name': clean_name(text[str(basic['PoolName']['Hash'])]),
                        'gachaType': row['gachaType'], 'rateUpItems5': up5, 'rateUpItems4': up4,
                        'replayable': True, 'source': 'HoYoLAB official announcement',
                        'sourceUrl': row['sourceUrl'], 'historicalStart': start, 'historicalEnd': end})
    indexed = {b['id']: b for b in banners}
    for group in load(resources / 'ExcelOutput/GachaGroupData.json'):
        marker = re.search(r'_(\d)(\d)0_([12])\.png$', group['PoolLabelIcon'])
        if marker:
            for banner_id in group['GachaIDList']:
                row = indexed[banner_id]
                if (row['version'], row['phaseIndex']) != (f'{marker[1]}.{marker[2]}', int(marker[3])):
                    raise ValueError(f'版本/期次与原始多池分组不符: {banner_id}')
    return {'sources': [{'project': 'HoYoLAB official announcements', 'count': len(notices),
                         'evidence': 'scripts/data/gm-banner-history-web.json',
                         'digestFormat': 'canonical-json-v1',
                         'sha256': hashlib.sha256(json.dumps(evidence, ensure_ascii=False, sort_keys=True,
                                                            separators=(',', ':')).encode('utf-8')).hexdigest(),
                         'verifiedAt': evidence['verifiedAt']}],
            'resourceCommit': load(ROOT / 'resources.lock.json')['commit'],
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
