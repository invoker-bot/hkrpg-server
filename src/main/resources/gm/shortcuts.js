import {api} from './api.js';
import {el,button,field,numberInput,searchInput,empty,table,targetPicker,renderSummary} from './ui.js';

const tags={hidden:'隐藏 / 解锁后显示',limited:'限定 / 排期来源',activity:'活动来源',collectible:'外观收藏','event-warp':'活动跃迁'};
const defaults=row=>({amount:1,level:row.item.maxLevel?1:0,rank:row.item.category==='equipment'?1:0});

export function mountShortcuts(main,inspector,state){
  let type='',tag='',query='',offset=0,sequence=0,selected=null,draft={},rows=[],types=[],loading=false;
  const typeSelect=el('select',{'aria-label':'快捷物品类型'}),tagSelect=el('select',{'aria-label':'收录依据'});
  typeSelect.append(el('option',{value:'',text:'全部类型'}));
  tagSelect.append(el('option',{value:'',text:'全部收录依据'}),...Object.entries(tags).map(([value,text])=>el('option',{value,text})));
  const list=el('div',{class:'shortcut-results'}),count=el('p',{class:'muted',role:'status'});
  const previous=button('上一页',()=>{offset=Math.max(0,offset-30);load();});
  const next=button('下一页',()=>{offset+=30;load();});
  const filter=()=>{type=typeSelect.value;tag=tagSelect.value;offset=0;selected=null;load();};
  typeSelect.addEventListener('change',filter);tagSelect.addEventListener('change',filter);
  main.append(el('p',{class:'shortcut-note',text:'按资源可见性、活动与跃迁来源整理。收录依据可在详情中查看；“限定来源”不等于已核实绝版。'}),
    el('div',{class:'shop-filters'},field('按类型分类',typeSelect),field('收录依据',tagSelect)),
    el('div',{class:'toolbar'},searchInput('搜索快捷物品名称、ID 或来源',value=>{query=value;offset=0;selected=null;load();}),button('刷新清单',load,'refresh')),
    count,list,el('div',{class:'pagination'},previous,next));

  function choose(row){
    if(selected?.item.id!==row.item.id)draft=defaults(row);
    selected=row;render();
    if(matchMedia('(max-width:740px)').matches)inspector.scrollIntoView({block:'start'});
  }

  async function grant(row,values){
    if(!state.target||state.busy||loading||!row.grantable)return;
    const item=row.item,target=state.target;
    const operation={action:'shortcut-grant',uid:target.uid,itemId:item.id,...values};
    const result=await state.run(operation,'确认快捷发放',[
      '目标：'+target.name+' · UID '+target.uid,'物品：'+item.name+' · #'+item.id,'数量：'+values.amount,
      ...(item.maxLevel?['等级：'+values.level]:[]),...(item.maxRank?['阶数：'+values.rank]:[]),
      row.note,'结果会保存到操作记录。',
    ]);
    if(result)render();
  }

  function renderInspector(){
    renderSummary(inspector,state);inspector.append(field('目标玩家',targetPicker(state)));
    if(!selected){inspector.append(el('p',{class:'inspector-note',text:'选择条目查看收录依据、设置数量或直接发放 1 份。'}));return;}
    const row=selected,item=row.item;
    inspector.append(el('section',{class:'action-group'},el('h3',{text:types.find(t=>t.id===row.type)?.name||row.type}),
      el('h2',{class:'resource-name',text:item.name}),el('p',{class:'mono muted',text:'ID '+item.id}),
      el('p',{class:'shortcut-tags',text:row.tags.map(t=>tags[t]||t).join(' · ')})),
      el('p',{class:row.grantable?'muted':'warning-text',text:row.note}));
    inspector.append(el('details',{class:'shortcut-sources'},el('summary',{text:'查看收录依据（'+row.sources.length+'）'}),
      el('ul',{},...row.sources.map(text=>el('li',{text})))));
    if(!row.grantable)return;
    const amount=numberInput(draft.amount,1,row.maxAmount),level=numberInput(draft.level,item.maxLevel?1:0,item.maxLevel||0),
      rank=numberInput(draft.rank,item.category==='equipment'?1:0,item.maxRank);
    for(const [name,input] of Object.entries({amount,level,rank}))input.addEventListener('input',()=>draft[name]=Number(input.value));
    inspector.append(field('数量',amount,'最多 '+row.maxAmount+'；外观收藏每次 1 件。'));
    if(item.maxLevel)inspector.append(field('等级',level));
    if(item.maxRank)inspector.append(field(item.category==='avatar'?'星魂':'叠影',rank));
    const send=button('预览并发放',()=>{
      if(!amount.reportValidity()||item.maxLevel&&!level.reportValidity()||item.maxRank&&!rank.reportValidity())return;
      grant(row,{amount:Number(amount.value),level:Number(level.value),rank:Number(rank.value)});
    },'box',true);send.disabled=state.busy||loading||!state.target;inspector.append(send);
  }

  function render(){
    renderInspector();
    list.replaceChildren(rows.length?table(['物品','类型 / 收录依据','操作'],rows.map(row=>{
      const item=row.item,quick=button('发放 1 份',()=>{choose(row);grant(row,defaults(row));},'box');
      quick.disabled=!row.grantable||!state.target||state.busy||loading;
      return el('tr',{class:selected?.item.id===item.id?'selected':''},
        el('td',{},el('strong',{text:item.name}),el('small',{class:'mono muted',text:'#'+item.id})),
        el('td',{},el('strong',{text:types.find(t=>t.id===row.type)?.name||row.type}),el('small',{class:'muted',text:row.tags.map(t=>tags[t]||t).join(' · ')}),
          ...(!row.grantable?[el('small',{class:'warning-text',text:'仅供查询'})]:[])),
        el('td',{},el('div',{class:'shortcut-actions'},button('查看',()=>choose(row)),quick)));
    })):empty(loading?'正在加载快捷清单…':'未找到匹配条目，请调整类型、收录依据或搜索条件。'));
  }

  async function load(){
    const current=++sequence;loading=true;render();list.setAttribute('aria-busy','true');previous.disabled=true;next.disabled=true;
    try{
      const data=await api('item-shortcuts?'+new URLSearchParams({type,tag,q:query,offset,limit:30}));
      if(current!==sequence||!main.isConnected)return;
      types=data.types;rows=data.items;
      typeSelect.replaceChildren(el('option',{value:'',text:'全部类型'}),...types.filter(t=>t.count).map(t=>el('option',{value:t.id,text:t.name+'（'+t.count+'）'})));
      typeSelect.value=type;count.textContent='共 '+data.total+' 项 · 本页 '+rows.length+' 项';
      if(selected)selected=rows.find(row=>row.item.id===selected.item.id)||null;
      previous.disabled=offset===0;next.disabled=offset+rows.length>=data.total;
    }catch(error){if(current===sequence&&main.isConnected){rows=[];count.textContent=error.message;}}
    finally{if(current===sequence){loading=false;list.removeAttribute('aria-busy');render();}}
  }
  load();return render;
}
