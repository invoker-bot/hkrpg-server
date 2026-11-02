"""离线将固定历史事实与 4.6 原始活动配置对应；缺少或歧义映射保持明确的待补项。"""
import argparse
import collections
from datetime import date
import hashlib
import json
from pathlib import Path
import re
import unicodedata

ROOT=Path(__file__).resolve().parents[1]


def load(path):return json.loads(path.read_text('utf-8-sig'))
def normalize(text):
    text=re.sub(r'<[^>]+>','',text)
    text=re.sub(r'第[一二三四五六七八九十百0-9]+期$','',text)
    return ''.join(c for c in unicodedata.normalize('NFKC',text) if c.isalnum())


def generate(facts=None):
    facts=load(ROOT/'scripts/data/gm-activity-history-web.json') if facts is None else facts
    banners=load(ROOT/'scripts/data/gm-banner-history-web.json')
    versions={r['version']:r for r in banners['versionSources']}
    if facts['schemaVersion']!=1 or set(facts['versions'])!=set(versions):raise ValueError('历史活动版本不完整')
    root=ROOT/'resources/4.6'; configs=load(root/'ExcelOutput/ActivityConfig.json')
    panels=load(root/'ExcelOutput/ActivityPanel.json');names=load(root/'TextMap/TextMapCHS.json')
    by_name=collections.defaultdict(list)
    for panel in panels:
        name=next((names.get(str(panel[field]['Hash'])) for field in ['TitleName','TabName'] if field in panel),None)
        if name:by_name[normalize(name)].append(panel)
    overrides=load(ROOT/'scripts/data/gm-activity-history-mappings.json')
    by_id={r['ActivityID']:r for r in configs}; by_panel={r['PanelID']:r for r in panels}
    results=[]
    for event in facts['events']:
        key,version=event['key'],event['version'];name=normalize(event['name'])
        if version not in versions or not event['sourceUrl'].startswith('https://wiki.biligame.com/sr/'):
            raise ValueError('活动版本或来源异常: '+key)
        start=event['startDate'][:10].replace('/','-')
        date.fromisoformat(start)
        # 分类为版本中的开放位置，不将这个推导冒充活动官方的独立期号。
        rows=[r for r in banners['banners'] if r['version']==version]
        boundaries=sorted({r['startDate'] for r in rows})
        phase_index=max(i for i,d in enumerate(boundaries,1) if d<=start)
        phase=('上半' if phase_index==1 else '下半') if len(boundaries)==2 else '第'+str(phase_index)+'期'
        candidates=by_name[name]
        if name=='巡星之礼':
            marker='Version0'+version.replace('.','')+'0'
            candidates=[r for r in candidates if marker in r.get('UIPrefab','') or version=='1.1' and r['PanelID']==10018]
        mapping=overrides.get(key)
        if mapping is not None:
            if not mapping.get('evidence'):raise ValueError('手工对应缺少依据: '+key)
            activity=by_id.get(mapping['activityId'])
            if activity is None:raise ValueError('对应不在当前资源中: '+key)
            panel=by_panel.get(activity.get('ActivityPanelID'))
            if mapping.get('family')=='excluded':
                if panel is not None:raise ValueError('不能将保留面板的活动标为缺失: '+key)
            elif panel is None:raise ValueError('对应缺少当前面板: '+key)
        elif len(candidates)==1:
            panel=candidates[0]
            matches=[r for r in configs if r.get('ActivityPanelID')==panel['PanelID']]
            activity=matches[0] if len(matches)==1 else None
        else:
            activity=None;panel=None
        modules=sorted(set(activity.get('ResidentModuleList',[])+activity.get('ActivityModuleIDList',[]))) if activity else []
        prefab=panel.get('UIPrefab','') if panel else ''
        family=('login' if 'SevenDay' in prefab else mapping.get('family','') if mapping else '')
        if not family:family='unverified'
        results.append({**event,'phase':phase,'phaseIndex':phase_index,'phaseBasis':'本版本已核对跃迁换期日期；以活动开始日期分类，原活动期次保留在名称中。',
                        'activityId':activity['ActivityID'] if activity else 0,'panelId':panel['PanelID'] if panel else 0,
                        'modules':modules,'residentModules':activity.get('ResidentModuleList',[]) if activity else [],'family':family,
                        'resourceStatus':'available' if activity and panel else 'missing-panel' if mapping and family=='excluded' else 'unresolved',
                        'mappingEvidence':mapping['evidence'] if mapping else '当前原始活动面板名称与显式 ActivityConfig 关联唯一匹配。' if activity else '资源对应缺失或存在歧义，需逐项补齐。'})
    if len({r['key'] for r in results})!=len(results):raise ValueError('活动身份重复')
    return {'schemaVersion':1,'versions':facts['versions'],'sources':facts['sources'],
            'factSHA256':hashlib.sha256(json.dumps(facts,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest(),
            'events':results,'excludedCount':len(facts['excluded'])}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    archive=generate();path=ROOT/'src/main/resources/gm/activity-history.json'
    if args.check:
        if load(path)!=archive:raise ValueError('已打包的活动档案与固定事实不一致')
    else:path.write_text(json.dumps(archive,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'events':len(archive['events']),'mapped':sum(r['activityId']>0 for r in archive['events']),
                      'loginPeriods':sum(r['family']=='login' for r in archive['events'])}))


if __name__=='__main__':main()
