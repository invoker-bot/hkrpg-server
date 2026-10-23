import {api} from './api.js';
import {el,button,field,numberInput,searchInput,empty,table} from './ui.js';

const typeNames={normal:'常驻池',newbie:'新手池',avatarup:'角色活动跃迁',weaponup:'光锥活动跃迁'};
export function mountBanners(main,inspector,state) {
  let mode='current',version='',type='',status='',query='',offset=0,selected=null,sequence=0,targetKey='';
  const versions=el('select',{'aria-label':'卡池版本'},el('option',{value:'',text:'全部版本'}));
  const types=el('select',{'aria-label':'卡池类型'});
  const statuses=el('select',{'aria-label':'卡池状态'},el('option',{value:'',text:'全部状态'}),
    ...Object.entries({active:'正在开放',stopped:'已停止',scheduled:'尚未开放',expired:'已到期'}).map(([value,text])=>el('option',{value,text})));
  const currentTab=button('当前卡池',()=>setMode('current')),historyTab=button('历史卡池',()=>setMode('history'));
  for(const tab of [currentTab,historyTab])tab.setAttribute('role','tab');
  const tabs=el('div',{class:'tabs',role:'tablist','aria-label':'卡池视图'},currentTab,historyTab);
  const versionField=field('版本',versions),statusField=field('状态',statuses);
  const count=el('span',{class:'muted','aria-live':'polite'}),list=el('div',{class:'banner-results'});
  const days=numberInput(30,1,365);days.setAttribute('aria-label','复刻天数');days.required=true;
  const previous=button('上一页',()=>{offset=Math.max(0,offset-30);load();});
  const next=button('下一页',()=>{offset+=30;load();});
  const search=searchInput('搜索卡池、内容名称或 ID',value=>{query=value;offset=0;selected=null;renderInspector();load();});
  const filter=()=>{version=versions.value;type=types.value;status=statuses.value;offset=0;selected=null;renderInspector();load();};
  versions.addEventListener('change',filter);types.addEventListener('change',filter);statuses.addEventListener('change',filter);
  main.append(tabs,el('div',{class:'banner-filters'},versionField,field('卡池类型',types),statusField),
    el('div',{class:'toolbar'},search,button('刷新卡池',load,'refresh')),count,list,
    el('div',{class:'pagination'},previous,next));

  function setMode(value,history=null) {
    mode=value;version=history?.version||'';type='';status='';query=history?String(history.id):'';
    offset=0;selected=null;targetKey=history?.historyKey||'';
    search.querySelector('input').value=query;statuses.value='';
    versionField.hidden=mode!=='history';statusField.hidden=mode!=='current';
    types.replaceChildren(el('option',{value:'',text:mode==='current'?'全部卡池类型':'角色与光锥'}),
      ...Object.entries(typeNames).filter(([key])=>mode==='current'||['avatarup','weaponup'].includes(key))
        .map(([value,text])=>el('option',{value,text})));
    for(const [tab,name] of [[currentTab,'current'],[historyTab,'history']]){
      tab.classList.toggle('active',mode===name);tab.setAttribute('aria-selected',String(mode===name));
    }
    renderInspector();load();
  }

  async function toggle(banner,enable) {
    const result=await state.run({action:enable?'banner-enable':'banner-stop',bannerId:banner.id},
      enable?'确认启用卡池':'确认停止卡池',[
        '全服生效：'+banner.name+' · #'+banner.id,
        enable?'恢复原配置与开放时间，不延长排期。':'立即停止开放；保存原配置，之后可重新启用或复刻。',
        '其他卡池和玩家保底记录保留。请重新打开游戏内跃迁页面。',
      ],true);
    if(result)await load();
  }

  function renderInspector() {
    inspector.replaceChildren(el('p',{class:'eyebrow',text:'全服卡池管理'}),
      el('h2',{text:selected?selected.name:mode==='current'?'选择当前卡池':'选择历史卡池'}));
    if(!selected){
      inspector.append(el('p',{class:'inspector-note',text:mode==='current'
        ?'查看本服实际配置，包括常驻、新手和自定义池。选择后可停止或恢复启用。'
        :'按版本查看历史 UP，选择后设置本次复刻天数。无需选择玩家。'}));return;
    }
    const banner=selected;
    inspector.append(el('p',{class:'mono muted',text:(mode==='history'
      ?'版本 '+banner.version+' · '+banner.phase:typeNames[banner.type.toLowerCase()])+' · #'+banner.id}),
      el('section',{class:'action-group'},el('h3',{text:mode==='history'?'历史 UP 内容':'本服配置内容'}),
        el('p',{text:(mode==='history'?'五星：':'五星配置：')+(banner.up5.map(i=>i.name).join('、')||'未设置专属条目')}),
        el('p',{class:'muted',text:(mode==='history'?'四星：':'四星配置：')+(banner.up4.map(i=>i.name).join('、')||'未设置专属条目')})));
    if(mode==='history'){
      inspector.append(el('section',{class:'action-group'},el('h3',{text:'官服历史时间 · UTC+8'}),
        el('p',{class:'muted',text:'开始：'+banner.historicalStart}),el('p',{class:'muted',text:'截止：'+banner.historicalEnd}),
        el('a',{class:'banner-source',href:banner.sourceUrl,target:'_blank',rel:'noopener noreferrer',text:'查看官方跃迁公告'})));
    }
    inspector.append(el('section',{class:'action-group'},el('h3',{text:'本服当前状态'}),el('p',{text:banner.status}),
      ...(banner.permanent?[el('p',{class:'muted',text:'长期开放，按启用状态控制。'})]
        :banner.endTime?[el('p',{class:'muted',text:'开始：'+date(banner.beginTime)}),el('p',{class:'muted',text:'截止：'+date(banner.endTime)})]:[])));
    if(mode==='current'){
      if(banner.canStop){const stop=button('停止卡池',()=>toggle(banner,false),'close');stop.disabled=state.busy;inspector.append(stop);}
      if(banner.disabled){
        const enable=button('恢复启用',()=>toggle(banner,true),'refresh',true);enable.disabled=state.busy||!banner.canEnable;inspector.append(enable);
        if(!banner.canEnable)inspector.append(el('p',{class:'inspector-note',text:'原开放时间已到期，请重新复刻或调整排期。'}));
      }
      if(banner.historyKey)inspector.append(button('查看历史模板',()=>setMode('history',{
        id:banner.id,version:banner.historyKey.split('-')[0],historyKey:banner.historyKey}),'log'));
      inspector.append(el('p',{class:'inspector-note',text:'此处显示本服实际配置。停止状态会保存，重启后继续生效；恢复启用沿用原开放时间。'}));
      return;
    }
    inspector.append(field('复刻天数',days,'1–365 天，从执行成功时开始；默认 30 天。'));
    const run=button('一键复刻',async()=>{
      if(!days.reportValidity())return;
      const durationDays=Number(days.value);
      const result=await state.run({action:'banner-rerun',bannerKey:banner.key,durationDays},'确认复刻卡池',[
        '全服生效：'+banner.name+' · 版本 '+banner.version+' · #'+banner.id,
        '五星 UP：'+banner.up5.map(i=>i.name).join('、'),'四星 UP：'+banner.up4.map(i=>i.name).join('、'),
        '本次开放 '+durationDays+' 天；相同 ID 的卡池将启用此历史 UP 和新的起止时间。',
        '其他卡池和玩家保底记录保留。',
      ]);
      if(result)await load();
    },'refresh',true);
    run.disabled=state.busy||!banner.replayable;inspector.append(run);
    if(['正在开放','尚未开放'].includes(banner.status)){
      const stop=button('停止当前卡池',()=>toggle(banner,false),'close');stop.disabled=state.busy;inspector.append(stop);
    }
    inspector.append(el('p',{class:'inspector-note',text:'复刻会重新启用该池并设置新的开放时间。操作后请重新打开游戏内跃迁页面。'}));
  }

  async function load() {
    const current=++sequence,view=mode;list.setAttribute('aria-busy','true');
    try {
      const data=await api('banners/'+view+'?'+new URLSearchParams({version,type,state:status,q:query,offset,limit:30}));
      if(current!==sequence||!main.isConnected)return;
      if(view==='history'){
        versions.replaceChildren(el('option',{value:'',text:'全部版本'}),...data.versions.map(v=>el('option',{value:v,text:'版本 '+v})));
        versions.value=version;
      }
      count.textContent='共 '+data.total+(view==='history'?' 个历史卡池':' 个已配置卡池');
      previous.disabled=offset===0;next.disabled=offset+data.items.length>=data.total;
      selected=targetKey?data.items.find(b=>b.key===targetKey)||null:selected?data.items.find(b=>b.id===selected.id)||null:null;
      targetKey='';
      const headers=view==='history'?['版本 / 期次','卡池','五星 UP','本服状态','选择']:['类型','卡池','五星配置','本服状态','选择'];
      list.replaceChildren(data.items.length?table(headers,data.items.map(b=>el('tr',{class:selected?.id===b.id?'selected':''},
        el('td',{},el('strong',{text:view==='history'?b.version:typeNames[b.type.toLowerCase()]}),
          el('small',{class:'muted',text:view==='history'?b.phase:b.permanent?'长期':b.version?'版本 '+b.version:''})),
        el('td',{},el('strong',{text:b.name}),el('small',{class:'muted',text:'#'+b.id})),
        el('td',{text:b.permanent?'常驻角色与光锥':b.up5.map(i=>i.name).join('、')||'未设置专属条目'}),
        el('td',{},el('span',{class:b.status==='正在开放'?'online-text':'muted',text:b.status})),
        el('td',{},button('查看',()=>{selected=b;renderInspector();
          for(const row of list.querySelectorAll('tbody tr'))row.classList.remove('selected');
          list.querySelectorAll('tbody tr')[data.items.indexOf(b)]?.classList.add('selected');}))))):empty('未找到匹配卡池，请调整筛选或名称。'));
      renderInspector();
    }catch(error){if(current===sequence&&main.isConnected)list.replaceChildren(empty(error.message),button('重新加载',load));}
    finally{if(current===sequence)list.removeAttribute('aria-busy');}
  }
  setMode('current');return renderInspector;
}

function date(seconds){return new Date(seconds*1000).toLocaleString('zh-CN',{hour12:false});}
