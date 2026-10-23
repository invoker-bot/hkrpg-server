"""复核固定公告事实；默认从已获取的公开 JSON 读取，--online 重新读取官方公开接口。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import html
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def normalized(value):
    return ''.join(c for c in html.unescape(re.sub(r'<[^>]+>', '', value)).casefold() if c.isalnum())


def verify(data, cache, online=False):
    def read(notice):
        if online:
            request = urllib.request.Request(notice['apiUrl'], headers={
                'User-Agent': 'hkrpg-banner-source-audit', 'x-rpc-language': 'en-us'})
            for attempt in range(3):
                try:
                    with urllib.request.urlopen(request, timeout=25) as response:
                        payload = json.load(response)
                    break
                except (TimeoutError, urllib.error.URLError):
                    if attempt == 2:
                        raise
                    time.sleep(.5)
        else:
            payload = json.loads((cache / f"official-{notice['postId']}.json").read_text(encoding='utf-8'))
        if payload['retcode'] != 0:
            raise ValueError(f"官方接口不可读取: {notice['url']}")
        post = payload['data']['post']['post']
        if (str(post['uid']) != '172534910' or post['game_id'] != 6
                or post['subject'] != notice['subject']):
            raise ValueError(f"公告身份不符: {notice['url']}")
        operations = json.loads(post['structured_content'])
        text = '\n'.join(op['insert'] for op in operations if isinstance(op.get('insert'), str))
        if hashlib.sha256(text.encode()).hexdigest() != notice['textSHA256']:
            raise ValueError(f"公告文本已变化，需要重新核对: {notice['url']}")
        return notice['url'], text

    with ThreadPoolExecutor(max_workers=3) as pool:
        texts = dict(pool.map(read, data['notices']))
    notices = {r['url']: r for r in data['notices']}
    for row in data['banners']:
        original_text = texts[row['sourceUrl']]
        text = normalized(original_text)
        dates = {f'{int(y):04}-{int(m):02}-{int(d):02}' for y, m, d in re.findall(
            r'(20\d{2})[/.-](\d{1,2})[/.-](\d{1,2})', original_text)}
        if row['endDate'] not in dates:
            raise ValueError(f"历史截止日期缺少公告依据: {row['id']}")
        for item_id in row['rateUpItems5'] + row['rateUpItems4']:
            item = data['itemEvidence'][str(item_id)]
            if any(normalized(item[key]) in text for key in ('name', 'zh')):
                continue
            notice = notices[row['sourceUrl']]
            if (row['verification'] == 'official_text_and_image' and item_id in row['rateUpItems4']
                    and item_id in notice.get('imageVerifiedItemIds', []) and notice.get('imageRosters')):
                continue
            raise ValueError(f"UP 名称缺少公告正文或已核对图片依据: {row['id']}/{item_id}")
    return {'notices': len(texts), 'banners': len(data['banners']),
            'imageVerifiedRows': sum(r['verification'] == 'official_text_and_image' for r in data['banners']),
            'passed': True, 'online': online}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--cache-directory', type=Path, default=ROOT / 'build/gm-banner-web-sources')
    parser.add_argument('--online', action='store_true')
    args = parser.parse_args()
    evidence = json.loads((ROOT / 'scripts/data/gm-banner-history-web.json').read_text(encoding='utf-8'))
    print(json.dumps(verify(evidence, args.cache_directory, args.online)))
