import {api} from './api.js';
import {el,button,field,numberInput,searchInput,empty,table} from './ui.js';

const typeNames={normal:'常驻池',newbie:'新手池',avatarup:'角色活动跃迁',weaponup:'光锥活动跃迁'};
export function mountBanners(main,inspector,state) {
  let mode='current',version='',type='',status='',query='',offset=0,selected=null,sequence=0,targetKey='';
  let pageItems=[],total=0,missing=0,selecting=false,loading=false;
  const checked=new Map();
  const versions=el('select',{'aria-label':'卡池版本'},el('option',{value:'',text:'全部版本'}));
  const types=el('select',{'aria-label':'卡池类型'});
  const statuses=el('select',{'aria-label':'卡池状态'},el('option',{value:'',text:'全部状态'}),
    ...Object.entries({active:'正在开放',stopped:'已停止',scheduled:'尚未开放',expired:'已到期'}).map(([value,text])=>el('option',{value,text})));
  const currentTab=button('已配置卡池',()=>setMode('current')),historyTab=button('历史卡池',()=>setMode('history'));
  for(const tab of [currentTab,historyTab])tab.setAttribute('role','tab');
  const tabs=el('div',{class:'tabs',role:'tablist','aria-label':'卡池视图'},currentTab,historyTab);
  const versionField=field('版本',versions),statusField=field('状态',statuses);
  const count=el('span',{class:'muted','aria-live':'polite'}),list=el('div',{class:'banner-results'});
  const days=numberInput(30,1,365);days.setAttribute('aria-label','复刻天数');days.required=true;
  const openingDays=numberInput(30,1,365);openingDays.setAttribute('aria-label','新卡池首次开放天数');openingDays.required=true;
  const openingField=field('新卡池首次开放天数',openingDays,'仅用于尚未安排时间的新活动池，已有排期保留。');
  const selectedCount=el('strong',{'aria-live':'polite',text:'已勾选 0 个'});
  const allMatches=button('全选筛选结果',selectMatches);
  const clear=button('清空勾选',()=>{checked.clear();renderBulk();});
  const batchEnable=button('批量启用',()=>batch(true),'refresh',true);
  const batchStop=button('批量停止',()=>batch(false),'close');
  const bulk=el('section',{class:'banner-bulk','aria-label':'批量管理卡池'},
    el('div',{class:'banner-bulk-actions'},selectedCount,allMatches,clear,batchEnable,batchStop),openingField);
  const catalogNote=el('span',{class:'muted'});
  const complete=button('补齐缺失配置',async()=>{
    const result=await state.run({action:'banner-complete'},'补齐缺失卡池配置',[
      '补入新手池及当前资源支持的历史活动池，新增配置默认停用。',
      '保留已有卡池的内容、启停状态与开放时间。',
    ]);
    if(result){if(result.state==='completed'){checked.clear();offset=0;}await load();}
  },'refresh');
  const catalog=el('div',{class:'banner-catalog'},catalogNote,complete);
  const previous=button('上一页',()=>{offset=Math.max(0,offset-30);load();});
  const next=button('下一页',()=>{offset+=30;load();});
  const search=searchInput('搜索卡池、内容名称或 ID',value=>{query=value;offset=0;selected=null;checked.clear();render();load();});
  const filter=()=>{version=versions.value;type=types.value;status=statuses.value;offset=0;selected=null;checked.clear();render();load();};
  versions.addEventListener('change',filter);types.addEventListener('change',filter);statuses.addEventListener('change',filter);
  main.append(tabs,el('div',{class:'banner-filters'},versionField,field('卡池类型',types),statusField),
    el('div',{class:'toolbar'},search,button('刷新卡池',()=>{checked.clear();load();},'refresh')),catalog,bulk,count,list,
    el('div',{class:'pagination'},previous,next));

  function setMode(value,history=null) {
    mode=value;version=history?.version||'';type='';status='';query=history?String(history.id):'';
    offset=0;selected=null;targetKey=history?.historyKey||'';
    checked.clear();pageItems=[];total=0;
    search.querySelector('input').value=query;statuses.value='';
    versionField.hidden=false;statusField.hidden=mode!=='current';
    types.replaceChildren(el('option',{value:'',text:mode==='current'?'全部卡池类型':'角色与光锥'}),
      ...Object.entries(typeNames).filter(([key])=>mode==='current'||['avatarup','weaponup'].includes(key))
        .map(([value,text])=>el('option',{value,text})));
    for(const [tab,name] of [[currentTab,'current'],[historyTab,'history']]){
      tab.classList.toggle('active',mode===name);tab.setAttribute('aria-selected',String(mode===name));
    }
    render();load();
  }

  function selectable(banner){return banner.canStop||banner.canEnable;}
  function checkbox(label,onChange){return el('input',{type:'checkbox',class:'banner-check','aria-label':label,onchange:onChange});}
  function render(){renderInspector();renderBulk();}
  function renderBulk(){
    const active=mode==='current';bulk.hidden=!active;catalog.hidden=!active;
    selectedCount.textContent='已勾选 '+checked.size+' 个';
    const rows=[...checked.values()];const blocked=state.busy||selecting||loading;
    allMatches.disabled=blocked||!total||total>500;clear.disabled=blocked||!checked.size;
    batchEnable.disabled=blocked||!rows.some(b=>b.canEnable);
    batchStop.disabled=blocked||!rows.some(b=>b.canStop);
    openingField.hidden=!rows.some(b=>b.needsSchedule);openingDays.disabled=blocked;
    complete.hidden=missing===0;complete.disabled=blocked;
    catalogNote.textContent=missing?'还有 '+missing+' 个卡池未配置，可一次补齐。':'当前资源支持的卡池目录已补齐。';
    for(const input of list.querySelectorAll('input[data-banner-id]')){
      const id=Number(input.dataset.bannerId),banner=pageItems.find(b=>b.id===id);
      input.checked=checked.has(id);input.disabled=blocked||!banner||!selectable(banner);
    }
    const header=list.querySelector('input[data-page-check]');
    if(header){const eligible=pageItems.filter(selectable),n=eligible.filter(b=>checked.has(b.id)).length;
      header.checked=eligible.length>0&&n===eligible.length;header.indeterminate=n>0&&n<eligible.length;header.disabled=blocked||!eligible.length;}
  }
  async function selectMatches(){
    if(state.busy||loading||selecting||mode!=='current')return;
    const generation=sequence;selecting=true;renderBulk();
    try{
      const pages=await Promise.all(Array.from({length:Math.ceil(total/100)},(_,i)=>
        api('banners/current?'+new URLSearchParams({version,type,state:status,q:query,offset:i*100,limit:100}))));
      if(generation!==sequence||mode!=='current'||!main.isConnected)return;
      for(const data of pages)for(const banner of data.items)if(selectable(banner))checked.set(banner.id,banner);
    }catch(error){if(generation===sequence)count.textContent='勾选失败：'+error.message;}
    finally{selecting=false;renderBulk();}
  }
  async function batch(enable){
    if(!checked.size||state.busy||loading||selecting)return;
    if(enable&&[...checked.values()].some(b=>b.needsSchedule)&&!openingDays.reportValidity())return;
    const rows=[...checked.values()],bannerIds=rows.map(b=>b.id).sort((a,b)=>a-b);
    const durationDays=Number(openingDays.value);
    const result=await state.run({action:enable?'banner-batch-enable':'banner-batch-stop',bannerIds,durationDays},
      enable?'确认批量启用卡池':'确认批量停止卡池',[
        '已勾选 '+rows.length+' 个卡池，全服生效。',
        rows.slice(0,5).map(b=>b.name+' #'+b.id).join('、')+(rows.length>5?' 等':''),
        enable?'尚未安排时间的新活动池首次开放 '+durationDays+' 天；已有卡池保留原排期。':'停止选中的卡池，内容和开放时间保留。',
        '已处于目标状态的卡池不重复修改；任一卡池无法修改时，整批保持原样。',
      ],true);
    if(result){if(result.state==='completed'){checked.clear();offset=0;}await load();}
  }

  async function toggle(banner,enable) {
    if(banner.needsSchedule&&!days.reportValidity())return;
    const result=await state.run({action:enable?'banner-enable':'banner-stop',bannerId:banner.id,durationDays:Number(days.value)},
      enable?'确认启用卡池':'确认停止卡池',[
        '全服生效：'+banner.name+' · #'+banner.id,
        enable?(banner.needsSchedule?'首次开放 '+days.value+' 天，从执行成功时开始。':'恢复原配置与开放时间，不延长排期。'):'立即停止开放；保存原配置，之后可重新启用或复刻。',
        '其他卡池和玩家保底记录保留。请重新打开游戏内跃迁页面。',
      ],true);
    if(result)await load();
  }

  function renderInspector() {
    inspector.replaceChildren(el('p',{class:'eyebrow',text:'全服卡池管理'}),
      el('h2',{text:selected?selected.name:mode==='current'?'选择已配置卡池':'选择历史卡池'}));
    if(!selected){
      inspector.append(el('p',{class:'inspector-note',text:mode==='current'
        ?'查看本服实际配置，包括常驻、新手和自定义池。选择后可停止或恢复启用。'
        :'按版本查看历史 UP，选择后设置本次复刻天数。无需选择玩家。'}));return;
    }
    const banner=selected;
    inspector.append(el('p',{class:'mono muted',text:(mode==='history'
      ?'版本 '+banner.version+' · '+banner.phase:typeNames[banner.type.toLowerCase()])+' · #'+banner.id}),
      el('section',{class:'action-group'},el('h3',{text:mode==='history'?'历史 UP 内容':banner.permanent?'实际可抽取内容':'本服 UP 内容'}),
        el('p',{text:(mode==='history'?'五星：':banner.permanent?'五星候选：':'五星 UP：')+(banner.up5.map(i=>i.name).join('、')||'未设置专属条目')}),
        el('p',{class:'muted',text:(mode==='history'?'四星：':banner.permanent?'四星候选：':'四星 UP：')+(banner.up4.map(i=>i.name).join('、')||'未设置专属条目')})));
    if(mode==='current'){
      if(banner.permanent)inspector.append(el('p',{class:'inspector-note',text:'常驻与新手池按真实星级分组，没有 UP；五星和四星各从对应候选中抽取。旧配置文件保持原样。'}));
      else inspector.append(el('section',{class:'action-group'},el('h3',{text:'实际可抽取内容（含非 UP）'}),
        el('p',{text:'五星候选：'+banner.pool5.map(i=>i.name).join('、')}),
        el('p',{class:'muted',text:'四星候选：'+banner.pool4.map(i=>i.name).join('、')})));
      if(!banner.pullable)inspector.append(el('section',{class:'action-group'},el('h3',{text:'配置需检查，当前不能抽取'}),
        ...banner.poolIssues.map(text=>el('p',{class:'inspector-note',text}))));
    }
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
        if(banner.needsSchedule)inspector.append(field('首次开放天数',days,'1–365 天，从启用成功时开始。'));
        const enable=button(banner.needsSchedule?'启用新卡池':'恢复启用',()=>toggle(banner,true),'refresh',true);enable.disabled=state.busy||!banner.canEnable;inspector.append(enable);
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
    const current=++sequence,view=mode;loading=true;renderBulk();list.setAttribute('aria-busy','true');
    try {
      const data=await api('banners/'+view+'?'+new URLSearchParams({version,type,state:status,q:query,offset,limit:30}));
      if(current!==sequence||!main.isConnected)return;
      versions.replaceChildren(el('option',{value:'',text:'全部版本'}),...(data.versions||[]).map(v=>el('option',{value:v,text:'版本 '+v})));
      versions.value=version;pageItems=data.items;total=data.total;missing=data.missingCount||0;
      for(const banner of pageItems)if(checked.has(banner.id)){if(selectable(banner))checked.set(banner.id,banner);else checked.delete(banner.id);}
      count.textContent='共 '+data.total+(view==='history'?' 个历史卡池':' 个已配置卡池');
      previous.disabled=offset===0;next.disabled=offset+data.items.length>=data.total;
      selected=targetKey?data.items.find(b=>b.key===targetKey)||null:selected?data.items.find(b=>b.id===selected.id)||null:null;
      targetKey='';
      const pageCheck=checkbox('勾选本页可操作卡池',event=>{
        for(const banner of pageItems.filter(selectable))if(event.target.checked)checked.set(banner.id,banner);else checked.delete(banner.id);renderBulk();
      });pageCheck.dataset.pageCheck='true';
      const headers=view==='history'?['版本 / 期次','卡池','五星 UP','本服状态','选择']:[pageCheck,'版本 / 类型','卡池','五星候选 / UP','本服状态','详情'];
      list.replaceChildren(data.items.length?table(headers,data.items.map(b=>el('tr',{class:selected?.id===b.id?'selected':''},
        ...(view==='current'?[el('td',{class:'banner-check-cell'},(()=>{const input=checkbox('勾选 '+b.name+' #'+b.id,event=>{
          if(event.target.checked)checked.set(b.id,b);else checked.delete(b.id);renderBulk();
        });input.dataset.bannerId=String(b.id);if(!selectable(b))input.title='已到期，请先通过历史模板重新复刻';return input;})())]:[]),
        el('td',{},el('strong',{text:view==='history'?b.version:b.permanent?'基础卡池':b.version?'版本 '+b.version:'自定义'}),
          el('small',{class:'muted',text:view==='history'?b.phase:typeNames[b.type.toLowerCase()]+(b.phase?' · '+b.phase:'')})),
        el('td',{},el('strong',{text:b.name}),el('small',{class:'muted',text:'#'+b.id})),
        el('td',{text:b.permanent?b.up5.length+' 个五星候选':b.up5.map(i=>i.name).join('、')||'未设置专属条目'}),
        el('td',{},el('span',{class:b.status==='正在开放'?'online-text':'muted',text:b.status})),
        el('td',{},button('查看',()=>{selected=b;renderInspector();
          for(const row of list.querySelectorAll('tbody tr'))row.classList.remove('selected');
          list.querySelectorAll('tbody tr')[data.items.indexOf(b)]?.classList.add('selected');}))))):empty('未找到匹配卡池，请调整筛选或名称。'));
      render();
    }catch(error){if(current===sequence&&main.isConnected)list.replaceChildren(empty(error.message),button('重新加载',load));}
    finally{if(current===sequence){loading=false;list.removeAttribute('aria-busy');renderBulk();}}
  }
  setMode('current');return render;
}

function date(seconds){return new Date(seconds*1000).toLocaleString('zh-CN',{hour12:false});}
