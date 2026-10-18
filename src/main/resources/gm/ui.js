const ns = 'http://www.w3.org/2000/svg';
const paths = {
  user: ['M20 21v-2a7 7 0 0 0-14 0v2Z', 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z'],
  box: ['m12 3 9 5v9l-9 5-9-5V8Z', 'm3 8 9 5 9-5', 'M12 13v9', 'm7.5 5.5 9 5'],
  mail: ['M3 5h18v14H3Z', 'm3 6 9 7 9-7'],
  terminal: ['m4 5 7 7-7 7', 'M14 19h7'],
  log: ['M5 3h14v18H5Z', 'M9 7h6', 'M9 11h6', 'M9 15h4'],
  refresh: ['M20 7V3l-4 4', 'M20 7a8 8 0 1 0 0 10', 'M20 3v4h-4'],
  search: ['M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0Z', 'm15 15 6 6'],
  arrow: ['m9 5 7 7-7 7'],
  heal: ['M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z', 'M12 7v10', 'M7 12h10'],
  bolt: ['m13 2-9 12h7l-1 8 10-13h-8Z'],
  warning: ['m12 3 10 18H2Z', 'M12 9v5', 'M12 17v.1'],
  info: ['M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z', 'M12 11v6', 'M12 7v.1'],
  close: ['m6 6 12 12', 'M18 6 6 18'],
};
export function icon(name) {
  const svg = document.createElementNS(ns, 'svg');
  for (const [k, v] of Object.entries({viewBox:'0 0 24 24', fill:'none', stroke:'currentColor', 'stroke-width':'1.7', 'stroke-linecap':'round', 'stroke-linejoin':'round', 'aria-hidden':'true'})) svg.setAttribute(k,v);
  for (const d of paths[name] || paths.box) { const p=document.createElementNS(ns,'path'); p.setAttribute('d',d); svg.append(p); }
  return svg;
}
export function el(tag, attrs = {}, ...children) {
  const element = document.createElement(tag);
  for (const [key,value] of Object.entries(attrs)) {
    if (key.startsWith('on')) element.addEventListener(key.slice(2),value);
    else if (key === 'class') element.className=value;
    else if (key === 'text') element.textContent=value;
    else if (key === 'disabled' || key === 'hidden') element[key]=value;
    else element.setAttribute(key,String(value));
  }
  element.append(...children.filter(v=>v!==null && v!==undefined));
  return element;
}
export const button = (text, action, symbol, primary = false) => el('button',{type:'button',class:primary?'button primary':'button',onclick:action},symbol?icon(symbol):null,el('span',{text}));
export function field(label,input,help) { return el('label',{class:'field'},el('span',{text:label}),input,help?el('small',{text:help}):null); }
export const numberInput = (value,min,max) => el('input',{type:'number',value,min,max,step:1,inputmode:'numeric'});
export function searchInput(placeholder,onChange) {
  const input=el('input',{type:'search',placeholder,'aria-label':placeholder});
  let timer;
  input.addEventListener('input',()=>{clearTimeout(timer);timer=setTimeout(()=>onChange(input.value),250);});
  return el('div',{class:'search'},icon('search'),input);
}
export function empty(text) { return el('div',{class:'empty-state'},icon('info'),el('p',{text})); }
export function targetPicker(state) {
  const select=el('select',{'aria-label':'目标玩家',required:true});
  select.append(el('option',{value:'',text:'请选择在线玩家'}));
  for(const player of state.players) select.append(el('option',{value:player.uid,text:`${player.name} · UID ${player.uid}`}));
  select.value=state.target?String(state.target.uid):'';
  select.addEventListener('change',()=>state.setTarget(Number(select.value)));
  return select;
}
export function showResult(result) {
  const states={completed:'已完成',returned:'指令已返回',partial:'部分完成',failed:'执行失败',uncertain:'结果待核对',pending:'执行中'};
  notice(states[result.state] || result.state,result.output,result.state==='completed' || result.state==='returned'?'ok':'warn');
}
export function notice(title,lines=[],kind='warn') {
  document.querySelector('#notice')?.remove();
  const close=button('关闭',()=>box.remove(),'close');
  close.setAttribute('aria-label','关闭提示');
  const box=el('section',{id:'notice',class:`notice ${kind}`,role:'status','aria-live':'polite'},
    el('div',{},el('strong',{text:title}),...lines.map(text=>el('p',{text}))),close);
  document.querySelector('#notifications').append(box);
}
export async function confirmOperation(title,details,dangerous=false) {
  return new Promise(resolve=>{
    const dialog=el('dialog',{'aria-labelledby':'confirm-title'});
    const finish=value=>{dialog.close();dialog.remove();resolve(value);};
    dialog.append(el('h2',{id:'confirm-title',text:title}),el('div',{class:'confirm-details'},...details.map(text=>el('p',{text}))),
      el('div',{class:'dialog-actions'},button('取消',()=>finish(false)),button(title.includes('邮件')?'确认发送':'确认执行',()=>finish(true),null,true)));
    dialog.addEventListener('cancel',event=>{event.preventDefault();finish(false);});
    document.body.append(dialog);dialog.showModal();
  });
}
export function table(headers,rows) {
  return el('div',{class:'table-scroll'},el('table',{},el('thead',{},el('tr',{},...headers.map(text=>el('th',{scope:'col',text})))),el('tbody',{},...rows)));
}
export function renderSummary(inspector,state) {
  inspector.replaceChildren(el('p',{class:'eyebrow',text:'当前目标'}));
  if(!state.target) {inspector.append(el('h2',{text:'尚未选择玩家'}),el('p',{class:'muted',text:'从玩家列表选择在线玩家。'}));return;}
  inspector.append(el('h2',{text:state.target.name}),el('p',{class:'mono muted',text:`UID ${state.target.uid}`}));
}
