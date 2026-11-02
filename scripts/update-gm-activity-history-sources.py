"""固定公开活动目录中的名称、版本、类别与日期事实，不复制正文或修改运行配置。"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
QUERY = '[[分类:活动]]|?名称|?开始描述|?结束描述|?类型|?所属版本|?TAG|?开始时间|?结束时间|sort=开始时间|order=asc|limit=1000'
API = 'https://wiki.biligame.com/sr/api.php?' + urllib.parse.urlencode({'action':'ask','query':QUERY,'format':'json'})


def extract(payload):
    events, excluded = [], []
    for title, row in payload['query']['results'].items():
        fields = row['printouts']
        if len(fields['所属版本']) != 1:
            raise ValueError('活动必须明确标注一个版本: ' + title)
        version = fields['所属版本'][0]
        item = {'key':version + '-' + hashlib.sha256(title.encode()).hexdigest()[:12],
                'version':version, 'name':title, 'categories':fields['类型'], 'tags':fields['TAG'],
                'historicalStart':fields['开始描述'][0], 'historicalEnd':fields['结束描述'][0],
                'startDate':fields['开始时间'][0], 'endDate':fields['结束时间'][0], 'sourceUrl':row['fullurl']}
        if tuple(map(int,version.split('.'))) > (4,6):
            excluded.append({**item,'reason':'future-version'})
        elif '网页活动' in fields['类型']:
            excluded.append({**item,'reason':'external-web-event'})
        else:
            events.append(item)
    if len({r['key'] for r in events + excluded}) != len(events) + len(excluded):
        raise ValueError('活动身份重复')
    return events, excluded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot',type=Path,help='使用已经读取的公开 API 快照')
    parser.add_argument('--output',type=Path,default=ROOT/'scripts/data/gm-activity-history-web.json')
    args = parser.parse_args()
    if args.snapshot:
        payload = json.loads(args.snapshot.read_text('utf-8'))
    else:
        with urllib.request.urlopen(API, timeout=40) as response:
            payload = json.load(response)
    events, excluded = extract(payload)
    versions = sorted({r['version'] for r in events},key=lambda v:tuple(map(int,v.split('.'))))
    expected = ['1.'+str(i) for i in range(7)]+['2.'+str(i) for i in range(8)]+['3.'+str(i) for i in range(9)]+['4.'+str(i) for i in range(7)]
    if versions != expected:
        raise ValueError('公开目录未覆盖 1.0–4.6 的全部 31 个版本')
    result = {'schemaVersion':1,'scope':'1.0–4.6 已发布游戏内活动；网页和未来版本分别排除。目录覆盖不等于玩法完成。',
              'sources':[{'url':'https://wiki.biligame.com/sr/活动一览','apiUrl':API,
                          'attribution':'崩坏：星穹铁道 BWIKI 活动目录贡献者；仅提取公开名称、版本、日期和分类事实。',
                          'factSHA256':hashlib.sha256(json.dumps(payload,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()}],
              'versions':versions,'events':events,'excluded':excluded}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'versions':len(versions),'events':len(events),'excluded':len(excluded)}))


if __name__ == '__main__':
    main()
