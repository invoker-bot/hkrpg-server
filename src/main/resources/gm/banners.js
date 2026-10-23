import {api} from './api.js';
import {el,button,field,numberInput,searchInput,empty,table} from './ui.js';

export function mountBanners(main,inspector,state) {
  let version='',type='',query='',offset=0,selected=null,sequence=0;
  const versions=el('select',{'aria-label':'卡池版本'},el('option',{value:'',text:'全部版本'}));
  const types=el('select',{'aria-label':'卡池类型'},el('option',{value:'',text:'角色与光锥'}),
    el('option',{value:'avatarup',text:'角色活动跃迁'}),el('option',{value:'weaponup',text:'光锥活动跃迁'}));
  const count=el('span',{class:'muted','aria-live':'polite'});
  const list=el('div',{class:'banner-results'});
  const days=numberInput(30,1,365);days.setAttribute('aria-label','复刻天数');days.required=true;
  const previous=button('上一页',()=>{offset=Math.max(0,offset-30);load();});
  const next=button('下一页',()=>{offset+=30;load();});
  const filter=()=>{version=versions.value;type=types.value;offset=0;selected=null;renderInspector();load();};
  versions.addEventListener('change',filter);types.addEventListener('change',filter);
  main.append(el('div',{class:'banner-filters'},field('版本',versions),field('卡池类型',types)),
    el('div',{class:'toolbar'},searchInput('搜索卡池、UP 名称或 ID',value=>{query=value;offset=0;selected=null;renderInspector();load();}),
      button('刷新卡池',load,'refresh')),count,list,el('div',{class:'pagination'},previous,next));

  function renderInspector() {
    inspector.replaceChildren(el('p',{class:'eyebrow',text:'全服卡池管理'}),
      el('h2',{text:selected?selected.name:'选择历史卡池'}),
      el('p',{class:'inspector-note',text:'按版本查看历史 UP，选择后设置本次复刻天数。无需选择玩家。'}));
    if(!selected)return;
    const banner=selected;
    inspector.append(el('p',{class:'mono muted',text:`版本 ${banner.version} · ${banner.phase} · #${banner.id}`}),
      el('section',{class:'action-group'},el('h3',{text:'历史 UP 内容'}),
        el('p',{text:`五星：${banner.up5.map(i=>i.name).join('、')}`}),
        el('p',{class:'muted',text:`四星：${banner.replayable?banner.up4.map(i=>i.name).join('、'):'资料待补全'}`})),
      el('section',{class:'action-group'},el('h3',{text:'本服当前配置'}),el('p',{text:banner.status}),
        ...(banner.endTime?[el('p',{class:'muted',text:`开始：${date(banner.beginTime)}`}),el('p',{class:'muted',text:`截止：${date(banner.endTime)}`})]:[])),
      field('复刻天数',days,'1–365 天，从执行成功时开始；默认 30 天。'));
    const run=button('一键复刻',async()=>{
      if(!days.reportValidity())return;
      const durationDays=Number(days.value);
      const result=await state.run({action:'banner-rerun',bannerKey:banner.key,durationDays},'确认复刻卡池',[
        `全服生效：${banner.name} · 版本 ${banner.version} · #${banner.id}`,
        `五星 UP：${banner.up5.map(i=>i.name).join('、')}`,
        `四星 UP：${banner.up4.map(i=>i.name).join('、')}`,
        `本次开放 ${durationDays} 天；相同 ID 的卡池将使用此历史 UP 和新的起止时间。`,
        '其他卡池和玩家保底记录保留。',
      ]);
      if(result)await load();
    },'refresh',true);
    run.disabled=state.busy||!banner.replayable;
    inspector.append(run,el('p',{class:'inspector-note',text:banner.replayable
      ?'复刻后重新打开游戏内跃迁页面。再次复刻同一池会重新设置开放时间。'
      :'已核对五星展示，但尚缺完整四星 UP 模板，暂不能复刻。'}));
  }

  async function load() {
    const current=++sequence;list.setAttribute('aria-busy','true');
    try {
      const data=await api(`banners/history?${new URLSearchParams({version,type,q:query,offset,limit:30})}`);
      if(current!==sequence||!main.isConnected)return;
      versions.replaceChildren(el('option',{value:'',text:'全部版本'}),...data.versions.map(v=>el('option',{value:v,text:`版本 ${v}`})));
      versions.value=version;count.textContent=`共 ${data.total} 个历史卡池`;
      previous.disabled=offset===0;next.disabled=offset+data.items.length>=data.total;
      if(selected)selected=data.items.find(b=>b.key===selected.key)||null;
      list.replaceChildren(data.items.length?table(['版本 / 期次','卡池','五星 UP','本服状态','选择'],data.items.map(b=>
        el('tr',{class:selected?.key===b.key?'selected':''},
          el('td',{},el('strong',{text:b.version}),el('small',{class:'muted',text:b.phase})),
          el('td',{},el('strong',{text:b.name}),el('small',{class:'muted',text:`${b.type==='AvatarUp'?'角色':'光锥'} · #${b.id}`})),
          el('td',{text:b.up5.map(i=>i.name).join('、')}),
          el('td',{},el('span',{class:b.status==='正在开放'?'online-text':'muted',text:b.status}),
            !b.replayable?el('small',{class:'warning-text',text:'四星 UP 待补全'}):null),
          el('td',{},button('查看',()=>{selected=b;renderInspector();
            for(const row of list.querySelectorAll('tbody tr'))row.classList.remove('selected');
            list.querySelectorAll('tbody tr')[data.items.indexOf(b)]?.classList.add('selected');}))))):empty('未找到匹配卡池，请调整版本、类型或名称。'));
      renderInspector();
    } catch(error) {if(current===sequence&&main.isConnected)list.replaceChildren(empty(error.message),button('重新加载',load));}
    finally {if(current===sequence)list.removeAttribute('aria-busy');}
  }
  renderInspector();load();return renderInspector;
}

function date(seconds) {return new Date(seconds*1000).toLocaleString('zh-CN',{hour12:false});}
