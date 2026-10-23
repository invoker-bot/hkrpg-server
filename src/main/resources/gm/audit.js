import {api} from './api.js';
import {el,button,searchInput,empty,table,renderSummary} from './ui.js';
const labels={grant:'资源发放',mail:'邮件发送',command:'指令',heal:'恢复生命',refill:'补满秘技点',unstuck:'解除卡死','banner-rerun':'卡池复刻','banner-stop':'停止卡池','banner-enable':'启用卡池'};
const states={completed:'已完成',returned:'已返回',partial:'部分完成',failed:'失败',uncertain:'待核对',pending:'结果未确认'};
export function mountAudit(main,inspector,state) {
  let entries=[],query='',selected=null;
  const list=el('div');
  main.append(el('div',{class:'toolbar'},searchInput('搜索 UID、操作或请求编号',value=>{query=value.toLowerCase();render();}),button('刷新记录',load,'refresh')),list);
  function render() {
    const rows=entries.filter(e=>`${e.uid} ${e.summary} ${e.requestId}`.toLowerCase().includes(query));
    list.replaceChildren(rows.length?table(['时间','目标 UID','操作','结果','详情'],rows.map(entry=>el('tr',{class:selected?.requestId===entry.requestId?'selected':''},
      el('td',{class:'mono',text:new Date(entry.at).toLocaleTimeString('zh-CN',{hour12:false})}),el('td',{class:'mono',text:entry.uid||(entry.action.startsWith('banner-')?'全服':'—')}),
      el('td',{text:labels[entry.action]||entry.action}),el('td',{class:['failed','partial','uncertain','pending'].includes(entry.state)?'warning-text':'online-text',text:states[entry.state]||entry.state}),
      el('td',{},button('查看',()=>{selected=entry;render();renderInspector();}))))):empty('暂无匹配的操作记录。'));
  }
  function renderInspector() {
    inspector.replaceChildren(el('p',{class:'eyebrow',text:'操作详情'}));
    if(!selected){inspector.append(el('h2',{text:'选择一条记录'}),el('p',{class:'muted',text:'保留最近 1000 项操作；结果未知时请先核对玩家状态。'}));return;}
    inspector.append(el('h2',{text:labels[selected.action]||selected.action}),el('p',{text:new Date(selected.at).toLocaleString('zh-CN')}),
      el('p',{class:'mono break',text:`${selected.action.startsWith('banner-')?'全服':`UID ${selected.uid}`} · ${selected.requestId}`}),el('hr'),el('p',{class:'break',text:selected.summary}),
      el('pre',{class:'terminal-output',text:selected.output.join('\n')}));
  }
  async function load(){try {entries=await api('audit');render();}catch(error){list.replaceChildren(empty(error.message));}}
  load();renderInspector();return renderInspector;
}
