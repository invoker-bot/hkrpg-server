import {api} from './api.js';
import {el,button,field,numberInput,searchInput,empty,table} from './ui.js';

const phases=['上半','下半','第1期','第2期','第3期'];
function time(seconds){return new Date(seconds*1000).toLocaleString('zh-CN',{hour12:false});}
function local(seconds){const d=new Date(seconds*1000);return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,19);}

export function mountActivityHistory(main,inspector,state){
  let query='',version='',phase='',offset=0,sequence=0,selected=null,items=[],loading=false;
  const versions=el('select',{'aria-label':'活动历史版本'}),periods=el('select',{'aria-label':'活动历史期次'});
  versions.append(el('option',{value:'',text:'全部版本'}));
  periods.append(el('option',{value:'',text:'全部期次'}),...phases.map(p=>el('option',{value:p,text:p})));
  const mode=el('select',{'aria-label':'复刻开放方式'}),days=numberInput(30,1,365);
  mode.append(el('option',{value:'days',text:'立即复刻，指定天数'}),el('option',{value:'custom',text:'自定义开始与结束时间'}));
  const begin=el('input',{type:'datetime-local',step:1,required:true}),end=el('input',{type:'datetime-local',step:1,required:true});
  mode.addEventListener('change',renderInspector);
  const list=el('div',{class:'activity-history-results'}),count=el('p',{class:'muted',role:'status'});
  const previous=button('上一页',()=>{offset=Math.max(0,offset-30);load();}),next=button('下一页',()=>{offset+=30;load();});
  function filter(){version=versions.value;phase=periods.value;offset=0;selected=null;load();}
  versions.addEventListener('change',filter);periods.addEventListener('change',filter);
  main.append(el('p',{class:'activity-note',text:'按 1.0–4.6 的版本与历史期次查看游戏内活动。每次复刻建立独立进度和领奖周期，原存档保留。'}),
    el('div',{class:'shop-filters'},field('历史版本',versions),field('历史期次',periods)),
    el('div',{class:'toolbar'},searchInput('搜索历史活动名称、版本、期次或编号',v=>{query=v;offset=0;selected=null;load();}),button('刷新历史活动',load,'refresh')),
    count,list,el('div',{class:'pagination'},previous,next));
  function choose(item){
    if(selected?.key!==item.key){const now=Math.floor(Date.now()/1000);begin.value=local(now);end.value=local(now+30*86400);mode.value='days';}
    selected=item;render();if(matchMedia('(max-width:740px)').matches)inspector.scrollIntoView({block:'start'});
  }
  async function operate(action){
    if(!selected||state.busy||loading)return;
    const item=selected,operation={action,activityKey:item.key};let description='';
    if(action==='activity-rerun'){
      if(!item.canReplay)return;
      if(mode.value==='days'){
        if(!days.reportValidity())return;
        operation.durationDays=Number(days.value);description='执行成功后开放 '+days.value+' 天。';
      }else{
        if(!begin.reportValidity()||!end.reportValidity())return;
        operation.beginTime=Math.floor(new Date(begin.value).getTime()/1000);operation.endTime=Math.floor(new Date(end.value).getTime()/1000);
        if(!Number.isFinite(operation.beginTime)||!Number.isFinite(operation.endTime)||operation.endTime<=operation.beginTime){
          end.setCustomValidity('结束时间必须晚于开始时间。');end.reportValidity();end.setCustomValidity('');return;
        }
        description='本服开始：'+time(operation.beginTime)+'；本服结束：'+time(operation.endTime)+'。';
      }
    }else operation.activityId=item.activityId;
    const title=action==='activity-rerun'?'确认复刻历史活动':action==='activity-stop'?'确认关闭本期复刻':'确认恢复本期复刻';
    const result=await state.run(operation,title,[
      '全服生效：'+item.version+' · '+item.phase+' · '+item.name,
      action==='activity-rerun'?description:action==='activity-stop'?'关闭本期，保留其排期、进度和领奖记录。':'恢复本期原有排期与周期，不延长时间或重置奖励。',
      action==='activity-rerun'?'这次复刻创建全新进度与领奖周期；原版及以前复刻的存档保留。':'操作只针对当前选择的历史期次。',
      '相同模块的已有排期将被替换；原始等级、任务及玩法门槛继续生效。',
      '请重新打开游戏活动页面；签到活动在实际登录时登记天数。',
    ]);
    if(result)await load();
  }
  function renderInspector(){
    inspector.replaceChildren(el('p',{class:'eyebrow',text:'历史活动复刻'}),el('h2',{class:'resource-name',text:selected?.name||'选择历史活动'}));
    if(!selected){inspector.append(el('p',{class:'inspector-note',text:'按版本和期次查找活动，再设置本服时间并一键复刻。无需选择玩家。'}));return;}
    const item=selected;
    inspector.append(el('p',{class:'mono muted',text:item.version+' · '+item.phase}),
      el('section',{class:'action-group'},el('h3',{text:'历史开放时间'}),el('p',{text:item.historicalStart+' → '+item.historicalEnd}),
        el('a',{class:'banner-source',href:item.sourceUrl,target:'_blank',rel:'noopener noreferrer',text:'查看历史资料来源'})),
      el('section',{class:'action-group'},el('h3',{text:item.status}),
        ...(item.beginTime?[el('p',{class:'muted',text:'本服开始：'+time(item.beginTime)}),el('p',{class:'muted',text:'本服结束：'+time(item.endTime)})]:[]),
        ...(item.replayId?[el('p',{class:'mono break',text:'周期 '+item.replayId})]:[])),
      el('p',{class:item.canReplay?'inspector-note':'warning-text',text:item.note}),
      el('details',{class:'activity-modules'},el('summary',{text:'查看活动对应与期次说明'}),
        el('p',{class:'mono break',text:'活动 #'+(item.activityId||'未对应')+' · 面板 #'+(item.panelId||'未对应')}),
        el('p',{class:'mono break',text:'模块 '+item.modules.join(' / ')}),
        el('p',{text:'期次按本版本换期日期和活动开始时间分类，历史活动的原期号保留在名称中。'})));
    if(!item.canReplay)return;
    inspector.append(field('复刻开放方式',mode));
    if(mode.value==='days')inspector.append(field('复刻天数',days,'1–365 天。'));
    else inspector.append(field('复刻开始时间',begin),field('复刻结束时间',end,'本地时区 '+Intl.DateTimeFormat().resolvedOptions().timeZone+'，最长 365 天。'));
    const rerun=button('一键复刻',()=>operate('activity-rerun'),'refresh',true);rerun.disabled=state.busy||loading;inspector.append(rerun);
    if(item.canStop){const stop=button('关闭本期复刻',()=>operate('activity-stop'),'close');stop.disabled=state.busy||loading;inspector.append(stop);}
    if(item.canEnable){const enable=button('按原周期恢复',()=>operate('activity-enable'),'refresh');enable.disabled=state.busy||loading;inspector.append(enable);}
  }
  function render(){
    renderInspector();list.replaceChildren();
    if(!items.length){list.append(empty(loading?'正在加载历史活动…':'未找到匹配的历史活动，请调整版本、期次或搜索条件。'));return;}
    const groups=new Map();for(const item of items){const key=item.version+' · '+item.phase;if(!groups.has(key))groups.set(key,[]);groups.get(key).push(item);}
    for(const [name,rows]of groups)list.append(el('section',{class:'activity-history-group'},el('h3',{text:name}),
      table(['历史活动','类型 / 状态','复刻','详情'],rows.map(item=>el('tr',{'data-key':item.key,class:selected?.key===item.key?'selected':''},
        el('td',{},el('strong',{text:item.name}),el('small',{class:'muted',text:item.historicalStart+' → '+item.historicalEnd})),
        el('td',{},el('span',{text:item.categories.join(' / ')}),el('small',{class:'muted',text:item.status})),
        el('td',{class:item.canReplay?'online-text':'warning-text',text:item.canReplay?'独立周期':item.family==='excluded'?'已排除':'待恢复'}),el('td',{},button('查看',()=>choose(item))))))));
  }
  async function load(){
    const request=++sequence;loading=true;render();previous.disabled=true;next.disabled=true;list.setAttribute('aria-busy','true');
    try{
      const data=await api('activities/history?'+new URLSearchParams({q:query,version,phase,offset,limit:30}));
      if(request!==sequence||!main.isConnected)return;
      if(versions.options.length===1)versions.append(...data.versions.map(v=>el('option',{value:v,text:v+' 版本'})));
      if(offset>0&&!data.items.length&&data.total<=offset){offset=Math.max(0,Math.floor((data.total-1)/30)*30);return load();}
      items=data.items;count.textContent='档案 '+data.catalogTotal+' 期 · 可复刻 '+data.replayableTotal+' 期 · 已排除 '+data.excludedTotal+' 期 · 待接通 '+data.pendingTotal+' 期 · 当前匹配 '+data.total+' 期';
      if(selected)selected=items.find(i=>i.key===selected.key)||null;
      previous.disabled=offset===0;next.disabled=offset+items.length>=data.total;
    }catch(error){if(request===sequence&&main.isConnected){items=[];selected=null;count.textContent=error.message;}}
    finally{if(request===sequence&&main.isConnected){loading=false;list.removeAttribute('aria-busy');render();}}
  }
  load();return render;
}
