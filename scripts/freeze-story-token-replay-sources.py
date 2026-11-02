"""固定三期千星旅伴的信物领取和原始104兑换商店，角色所有权继续共用原规则。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
EDITIONS={10093:'3.2-832ca91ea2ff',10125:'3.7-0296b910ede6',10141:'4.2-8a434e481901'}
spec=importlib.util.spec_from_file_location('signin_sources',ROOT/'scripts/freeze-signin-gift-replay-sources.py')
signin=importlib.util.module_from_spec(spec);spec.loader.exec_module(signin)


def generate():
    result=signin.generate(EDITIONS)
    assert all(r['RewardList']==[1201] for r in result['records']['ActivityLoginConfig'])
    assert all(r['UIPrefab']=='UI/Quest/Widget/QuestStoryTokensPanel.prefab' for r in result['records']['ActivityPanel'])
    assert result['records']['RewardData']==[{'RewardID':1201,'ItemID_1':281,'Count_1':1}]
    historical=json.loads((ROOT/'scripts/data/gm-story-token-edition-sources.json').read_text('utf8'))
    assert {r['activityId'] for r in historical['editions']}==set(EDITIONS)
    for edition in historical['editions']:
        activity=edition['activityId'];files=edition['files']
        assert files['ActivityConfig']['row']['ActivityID']==activity
        current=next(r for r in result['records']['ActivityLoginConfig'] if r['ID']==activity)
        assert files['ActivityLoginConfig']['row']==current
        assert files['RewardData']['row']==result['records']['RewardData'][0]
        panel=files['ActivityPanel']['row']
        if panel is None:assert not files['ActivityConfig']['row'].get('ActivityPanelID')
        else:assert panel['PanelID']==activity and panel['UIPrefab']=='UI/Quest/Widget/QuestStoryTokensPanel.prefab'
    result['historicalEditions']=historical['editions']
    def read(name):
        path=ROOT/'resources/4.6/ExcelOutput'/(name+'.json');raw=path.read_bytes();rows=json.loads(raw)
        result['sources']['ExcelOutput/'+path.name]={'sha256':hashlib.sha256(raw).hexdigest(),'rowCount':len(rows)}
        return rows
    result['records']['ShopConfig']=[r for r in read('ShopConfig') if r['ShopID']==104]
    result['records']['ShopGoodsConfig']=[r for r in read('ShopGoodsConfig') if r['ShopID']==104]
    cards={r['ID'] for r in read('ItemConfigAvatar')}
    result['avatarGoods']=[r for r in result['records']['ShopGoodsConfig'] if r['ItemID'] in cards]
    assert len(result['avatarGoods'])==12
    assert all(r['CurrencyList']==[281] and r['CurrencyCostList']==[1] and r['ItemCount']==1
               and r['LimitType1']=='AvatarNum' for r in result['avatarGoods'])
    result['scope']='各期独立领取281信物；原始104商店实际扣费兑换，角色及星魂上限和商店原有收据保留。'
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    result=generate();path=ROOT/'src/main/resources/gameplay/story-token-replay-4.6.json'
    if args.check:assert json.loads(path.read_text('utf8'))==result,'千星旅伴固定来源已变化'
    else:path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf8')
    print(json.dumps({'editions':len(EDITIONS),'avatars':len(result['avatarGoods'])}))
