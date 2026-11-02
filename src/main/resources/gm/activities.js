import {api} from './api.js';
import {el,button,field,numberInput,searchInput,empty,table} from './ui.js';
import {mountActivityHistory} from './activity-history.js';

const typeNames={event:'活动模块',resident:'常驻模块',trial:'角色试用',unmapped:'缺少面板关联'};
const states={unconfigured:'未配置',active:'正在开放',scheduled:'尚未开放',stopped:'已关闭',expired:'已到期',mixed:'模块排期不同'};
function date(seconds){const d=new Date(seconds*1000);return Number.isFinite(d.getTime())?d.toLocaleString('zh-CN',{hour12:false}):'超出日期显示范围';}
function local(seconds){const d=new Date(seconds*1000);return Number.isFinite(d.getTime())?new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,19):'';}

export function mountActivities(main,inspector,state){
  let update=()=>{};
  const history=button('历史活动复刻',()=>mount('history'),'refresh'),current=button('原始资源排期',()=>mount('current'),'log');
  const content=el('div');main.append(el('div',{class:'activity-view-tabs'},history,current),content);
  function mount(view){content.replaceChildren();inspector.replaceChildren();history.classList.toggle('primary',view==='history');current.classList.toggle('primary',view==='current');
    update=(view==='history'?mountActivityHistory:mountActivitySchedules)(content,inspector,state);}
  mount('history');return()=>update();
}

function mountActivitySchedules(main,inspector,state){
  let query='',type='',status='',offset=0,sequence=0,selected=null,rows=[],loading=false;
  const types=el('select',{'aria-label':'活动类型'}),statuses=el('select',{'aria-label':'活动状态'});
  types.append(el('option',{value:'',text:'全部活动类型'}),...Object.entries(typeNames).map(([value,text])=>el('option',{value,text})));
  statuses.append(el('option',{value:'',text:'全部本服状态'}),...Object.entries(states).map(([value,text])=>el('option',{value,text})));
  const mode=el('select',{'aria-label':'开放方式'}),days=numberInput(30,1,365);
  mode.append(el('option',{value:'days',text:'立即开放，指定天数'}),el('option',{value:'custom',text:'自定义开始与结束时间'}));
  const begin=el('input',{type:'datetime-local',step:1,required:true}),end=el('input',{type:'datetime-local',step:1,required:true});
  mode.addEventListener('change',renderInspector);
  const list=el('div',{class:'activity-results'}),count=el('p',{class:'muted',role:'status'});
  const previous=button('上一页',()=>{offset=Math.max(0,offset-30);load();}),next=button('下一页',()=>{offset+=30;load();});
  function filter(){type=types.value;status=statuses.value;offset=0;selected=null;load();}
  types.addEventListener('change',filter);statuses.addEventListener('change',filter);
  main.append(el('p',{class:'activity-note',text:'按当前资源目录管理往期与现有活动的本服排期。玩法支持范围、原始解锁条件和已领奖记录继续生效。'}),
    el('div',{class:'shop-filters'},field('活动类型',types),field('本服状态',statuses)),
    el('div',{class:'toolbar'},searchInput('搜索活动名称、活动 / 面板 / 模块 ID',value=>{query=value;offset=0;selected=null;load();}),button('刷新活动',load,'refresh')),
    count,list,el('div',{class:'pagination'},previous,next));

  function choose(activity){
    if(selected?.id!==activity.id){
      const now=Math.floor(Date.now()/1000);mode.value='days';
      begin.value=local(activity.beginTime||now);end.value=local(activity.endTime&&activity.endTime>now?activity.endTime:now+30*86400);
    }
    selected=activity;render();
    if(matchMedia('(max-width:740px)').matches)inspector.scrollIntoView({block:'start'});
  }

  async function operate(action){
    if(!selected?.canManage||state.busy||loading)return;
    const activity=selected,operation={action,activityId:activity.id};let windowText='';
    if(action==='activity-open'){
      if(mode.value==='days'){
        if(!days.reportValidity())return;
        operation.durationDays=Number(days.value);windowText='从执行成功时开始，开放 '+days.value+' 天。';
      }else{
        if(!begin.reportValidity()||!end.reportValidity())return;
        operation.beginTime=Math.floor(new Date(begin.value).getTime()/1000);
        operation.endTime=Math.floor(new Date(end.value).getTime()/1000);
        if(!Number.isFinite(operation.beginTime)||!Number.isFinite(operation.endTime)||operation.endTime<=operation.beginTime){
          end.setCustomValidity('结束时间必须晚于开始时间。');end.reportValidity();end.setCustomValidity('');return;
        }
        windowText='开始：'+date(operation.beginTime)+'；结束：'+date(operation.endTime)+'。';
      }
    }
    const title=action==='activity-stop'?'确认关闭活动':action==='activity-enable'?'确认恢复活动':'确认开放活动';
    const result=await state.run(operation,title,[
      '全服生效：'+activity.name+' · #'+activity.id,'包含 '+activity.modules.length+' 个原始模块。',
      action==='activity-open'?windowText:action==='activity-stop'?'关闭这些模块，保存原开放时间，之后可恢复或重新开放。':'恢复原配置的开放时间，不延长排期。',
      '玩家进度和已领奖记录保留，不会重新发放已领取奖励；原始解锁条件继续生效。',
      '请重新打开游戏活动页面查看最新排期。',
    ]);
    if(result)await load();
  }

  function renderInspector(){
    inspector.replaceChildren(el('p',{class:'eyebrow',text:'全服活动排期'}),el('h2',{class:'resource-name',text:selected?selected.name:'选择活动'}));
    if(!selected){inspector.append(el('p',{class:'inspector-note',text:'按名称或编号查找活动。选择后可设置开放窗口、关闭，或按原排期恢复；无需选择玩家。'}));return;}
    const activity=selected,blocked=loading||state.busy;
    inspector.append(el('p',{class:'mono muted',text:'活动 #'+activity.id+' · 面板 #'+(activity.panelId||'未关联')}),
      el('section',{class:'action-group'},el('h3',{text:activity.status}),el('p',{text:typeNames[activity.type]}),
        ...(activity.beginTime||activity.endTime?[el('p',{class:'muted',text:'开始：'+date(activity.beginTime)}),el('p',{class:'muted',text:'结束：'+date(activity.endTime)})]:[])),
      el('details',{class:'activity-modules'},el('summary',{text:'查看 '+activity.modules.length+' 个模块与排期'}),
        el('ul',{},...activity.modules.map(m=>el('li',{},el('strong',{text:'#'+m.id+' · '+(m.resident?'常驻模块':'活动模块')}),
          el('p',{text:states[m.state]}),...(m.configured?[el('small',{text:date(m.beginTime)+' → '+date(m.endTime)})]:[]))))),
      el('p',{class:activity.canManage?'inspector-note':'warning-text',text:activity.note}));
    if(activity.unlockConditions)inspector.append(el('details',{class:'activity-modules'},el('summary',{text:'原始解锁条件'}),el('p',{class:'mono break',text:activity.unlockConditions})));
    if(!activity.canManage)return;
    inspector.append(field('开放方式',mode));
    if(mode.value==='days')inspector.append(field('开放天数',days,'1–365 天，从执行成功时开始。'));
    else inspector.append(field('开始时间',begin),field('结束时间',end,'按浏览器本地时区 '+Intl.DateTimeFormat().resolvedOptions().timeZone+' 输入，最长 365 天。'));
    const open=button(activity.state==='unconfigured'?'开放活动':'设置新的开放窗口',()=>operate('activity-open'),'refresh',true);
    open.disabled=blocked;inspector.append(open);
    if(activity.canStop){const stop=button('关闭活动',()=>operate('activity-stop'),'close');stop.disabled=blocked;inspector.append(stop);}
    if(activity.canEnable){const enable=button('按原排期恢复',()=>operate('activity-enable'),'refresh');enable.disabled=blocked;inspector.append(enable);}
    if(activity.state==='expired')inspector.append(el('p',{class:'inspector-note',text:'原排期已到期，设置新窗口即可重新开放，已领取的奖励不会重置。'}));
  }

  function render(){
    renderInspector();
    list.replaceChildren(rows.length?table(['活动','类型 / 模块','本服状态 / 时间','详情'],rows.map(a=>el('tr',{class:selected?.id===a.id?'selected':''},
      el('td',{},el('strong',{text:a.name}),el('small',{class:'mono muted',text:'活动 #'+a.id+' · 面板 #'+(a.panelId||'—')})),
      el('td',{},el('span',{text:typeNames[a.type]}),el('small',{class:a.canManage?'muted':'warning-text',text:a.modules.length+' 个模块'+(a.canManage?'':' · 仅查询')})),
      el('td',{},el('strong',{class:a.state==='active'?'online-text':'muted',text:a.status}),
        ...(a.endTime?[el('small',{class:'muted',text:date(a.beginTime)}),el('small',{class:'muted',text:'至 '+date(a.endTime)})]:[])),
      el('td',{},button('查看',()=>choose(a)))))):empty(loading?'正在加载活动目录…':'未找到匹配活动，请调整筛选或搜索条件。'));
  }

  async function load(){
    const current=++sequence;loading=true;render();list.setAttribute('aria-busy','true');previous.disabled=true;next.disabled=true;
    try{
      const data=await api('activities?'+new URLSearchParams({q:query,type,state:status,offset,limit:30}));
      if(current!==sequence||!main.isConnected)return;
      if(offset>0&&!data.items.length&&data.total<=offset){offset=Math.max(0,Math.floor((data.total-1)/30)*30);return load();}
      rows=data.items;count.textContent='共 '+data.total+' 个活动 · 本页 '+rows.length+' 个';
      if(selected)selected=rows.find(a=>a.id===selected.id)||null;
      previous.disabled=offset===0;next.disabled=offset+rows.length>=data.total;
    }catch(error){if(current===sequence&&main.isConnected){rows=[];selected=null;count.textContent=error.message;}}
    finally{if(current===sequence&&main.isConnected){loading=false;list.removeAttribute('aria-busy');render();}}
  }
  load();return render;
}
