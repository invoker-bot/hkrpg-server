import {api} from './api.js';
import {el,button,field,numberInput,searchInput,empty,table,targetPicker,renderSummary} from './ui.js';
const categories=[['','全部'],['item','物品'],['avatar','角色'],['equipment','光锥'],['relic','遗器']];
export function mountResources(main,inspector,state) {
  let category='',query='',offset=0,selection=null,sequence=0;
  const tabs=el('div',{class:'tabs',role:'group','aria-label':'资源分类'});
  const results=el('div',{class:'catalog-results'});const pageInfo=el('span',{class:'muted'});
  for(const [value,label] of categories) tabs.append(button(label,()=>{category=value;offset=0;load();}));
  const prev=button('上一页',()=>{offset=Math.max(0,offset-30);load();});
  const next=button('下一页',()=>{offset+=30;load();});
  main.append(tabs,el('div',{class:'toolbar'},searchInput('搜索物品、角色名称或 ID',value=>{query=value;offset=0;load();}),pageInfo),results,el('div',{class:'pagination'},prev,next));
  function renderInspector() {
    renderSummary(inspector,state);
    inspector.append(field('目标玩家',targetPicker(state)));
    if(!selection) {inspector.append(el('p',{class:'inspector-note',text:'选择资源，查看参数并预览发放。'}));return;}
    const item=selection;
    const amount=numberInput(1,1,item.category==='avatar'?1:['equipment','relic'].includes(item.category)?100:1000000);
    const level=numberInput(item.maxLevel?1:0,item.maxLevel?1:0,item.maxLevel||0);
    const rank=numberInput(item.category==='equipment'?1:0,item.category==='equipment'?1:0,item.maxRank);
    inspector.append(el('section',{class:'action-group'},el('h3',{text:'发放预览'}),el('h2',{class:'resource-name',text:item.name}),el('p',{class:'mono muted',text:`ID ${item.id}`}),field('数量',amount)));
    if(item.maxLevel) inspector.append(field('等级',level));
    if(item.maxRank) inspector.append(field(item.category==='avatar'?'星魂':'叠影',rank));
    const send=button('预览并发放',()=>{
      const target=state.target;if(!target)return;
      if(!amount.reportValidity() || item.maxLevel&&!level.reportValidity() || item.maxRank&&!rank.reportValidity())return;
      const data={action:'grant',uid:target.uid,itemId:item.id,amount:Number(amount.value),level:Number(level.value),rank:Number(rank.value)};
      state.run(data,'确认发放资源',[`目标：${target.name} · UID ${target.uid}`,`资源：${item.name} · #${item.id}`,`数量：${data.amount}`,...(item.maxLevel?[`等级：${data.level}`]:[]),...(item.maxRank?[`${item.category==='avatar'?'星魂':'叠影'}：${data.rank}`]:[])]);
    },'box',true);
    send.disabled=!state.target || state.busy;inspector.append(send,el('p',{class:'inspector-note',text:'已有角色不会重复发放。背包限制与实际入库结果以服务端回显为准。'}));
  }
  async function load() {
    const current=++sequence;
    [...tabs.children].forEach((b,i)=>b.classList.toggle('active',categories[i][0]===category));
    results.setAttribute('aria-busy','true');
    try {
      const data=await api(`catalog?${new URLSearchParams({q:query,category,offset,limit:30})}`);
      if(current!==sequence || !main.isConnected)return;
      pageInfo.textContent=`共 ${data.total} 项`;
      prev.disabled=offset===0;next.disabled=offset+data.items.length>=data.total;
      results.replaceChildren(data.items.length?table(['名称','ID','类型','选择'],data.items.map(item=>{
        const choose=button('选择',()=>{selection=item;renderInspector();load();});
        return el('tr',{class:selection?.id===item.id?'selected':''},el('td',{text:item.name}),el('td',{class:'mono',text:item.id}),el('td',{class:'muted',text:categories.find(c=>c[0]===item.category)?.[1]||'物品'}),el('td',{},choose));
      })):empty('未找到匹配资源，请尝试名称或 ID。'));
    } catch(error) {if(current===sequence) results.replaceChildren(empty(error.message),button('重新加载',load));}
    finally {if(current===sequence)results.removeAttribute('aria-busy');}
  }
  renderInspector();load();
  return renderInspector;
}
