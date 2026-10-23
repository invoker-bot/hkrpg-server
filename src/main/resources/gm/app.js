import {api,session,operate,pendingOperation,recoverOperation,acknowledgePending} from './api.js';
import {el,icon,button,notice,showResult,confirmOperation} from './ui.js';
import {mountPlayers} from './players.js';
import {mountResources} from './resources.js';
import {mountMail} from './mail.js';
import {mountTerminal} from './terminal.js';
import {mountAudit} from './audit.js';
import {mountBanners} from './banners.js';

const pages={
  players:{label:'玩家管理',description:'选择在线玩家，执行常用管理操作。',icon:'user',mount:mountPlayers},
  resources:{label:'资源发放',description:'搜索物品与角色，核对参数后发放给目标玩家。',icon:'box',mount:mountResources},
  mail:{label:'邮件发送',description:'编辑系统邮件与附件，发送到目标玩家的邮箱。',icon:'mail',mount:mountMail},
  banners:{label:'卡池管理',description:'查看当前卡池、管理启停，或按版本复刻历史 UP。',icon:'refresh',mount:mountBanners},
  terminal:{label:'指令终端',description:'执行现有 GM 指令，并查看本次调用的回显。',icon:'terminal',mount:mountTerminal},
  audit:{label:'操作记录',description:'核对执行结果、目标 UID 与请求编号。',icon:'log',mount:mountAudit},
};
const root=document.querySelector('#app');
let updatePage=()=>{},refreshTimer,currentPage='players',main,inspector,statusStrip,footer,connection;
const state={players:[],target:null,busy:false,
  setTarget(uid){state.target=state.players.find(p=>p.uid===uid)||null;updatePage();},
  navigate(page){navigate(page);},
  async run(operation,title,details,dangerous=false){
    if(state.busy)return;
    if(!await confirmOperation(title,details,dangerous))return;
    if(state.busy)return;
    state.busy=true;updatePage();
    try {const result=await operate(operation);showResult(result);await refresh();return result;}
    catch(error){notice('操作未完成',[error.message]);}
    finally{state.busy=false;updatePage();renderPending();}
  },
};
function brand(){return el('div',{class:'brand'},el('strong',{text:'HKRPG'}),el('span',{text:'GM 控制台'}));}
function connectionScreen(message) {
  clearInterval(refreshTimer);
  const panel=el('section',{class:'connection-panel'},brand(),el('h1',{text:'连接 GM 控制台'}),
    el('p',{class:'connection-error',role:'alert',text:message}),button('重新连接',connect,'refresh',true));
  root.replaceChildren(el('main',{class:'connection-screen'},panel));
}
async function connect(){
  try {await session();await shell();}
  catch(error){connectionScreen(error.message);}
}
async function shell(){
  clearInterval(refreshTimer);
  connection=el('span',{class:'connection',text:'正在连接'});
  const header=el('header',{class:'header'},brand(),el('div',{class:'header-actions'},connection,
    button('刷新',()=>refresh().catch(error=>notice('刷新失败',[error.message])),'refresh')));
  const nav=el('nav',{'aria-label':'管理导航'});
  for(const [name,page] of Object.entries(pages)) nav.append(el('button',{type:'button','data-page':name,onclick:()=>navigate(name)},icon(page.icon),el('span',{text:page.label})));
  const sidebar=el('aside',{class:'sidebar'},nav,el('p',{class:'sidebar-footer',text:'LunarCore · 本地管理'}));
  main=el('main',{class:'main'});inspector=el('aside',{class:'inspector','aria-label':'目标与操作'});
  root.replaceChildren(el('div',{class:'shell'},header,sidebar,el('div',{class:'workspace'},main,inspector)));
  navigate(currentPage);renderPending();
  await refresh();
  refreshTimer=setInterval(()=>{if(!state.busy)refresh().catch(error=>{connection.textContent='连接异常';connection.classList.remove('ready');});},15000);
}
function navigate(page){
  if(!pages[page]||!main)return;
  currentPage=page;
  for(const b of document.querySelectorAll('[data-page]')){
    const active=b.dataset.page===page;b.classList.toggle('active',active);
    if(active)b.setAttribute('aria-current','page');else b.removeAttribute('aria-current');
  }
  statusStrip=el('dl',{class:'status-strip'});
  const content=el('div',{class:'page-content'});
  const footerText=page==='banners'?'卡池配置全服生效，启停与复刻结果记入操作记录。':page==='terminal'?'目标 UID 以当前指令的支持范围为准。':page==='audit'?'最近 1000 项操作，按时间倒序显示。':'仅对当前在线玩家执行操作。';
  footer=el('footer',{class:'main-footer'},el('span',{},icon('info'),el('span',{text:footerText})),el('time',{text:state.lastRefreshed?`上次刷新 ${state.lastRefreshed}`:'等待刷新'}),el('span',{text:'连接到当前游戏服务'}));
  main.replaceChildren(el('div',{class:'page-heading'},el('h1',{text:pages[page].label}),el('p',{text:pages[page].description})),statusStrip,content,footer);
  inspector.replaceChildren();
  updatePage=pages[page].mount(content,inspector,state);
  if(state.status)renderStatus(state.status);
  renderPending();
}
async function refresh(){
  const [status,players]=await Promise.all([api('status'),api('players')]);
  if(!main?.isConnected)return;
  state.status=status;renderStatus(status);
  const changed=JSON.stringify(players)!==JSON.stringify(state.players);
  state.players=players;
  if(state.target)state.target=players.find(p=>p.uid===state.target.uid)||null;
  if(changed)updatePage();
  state.lastRefreshed=new Date().toLocaleTimeString('zh-CN',{hour12:false});
  footer.querySelector('time').textContent=`上次刷新 ${state.lastRefreshed}`;
}
function renderStatus(status){
  connection.textContent=status.ready?(status.gameAvailable?'服务就绪':'仅 Dispatch 服务'):'服务准备中';connection.classList.toggle('ready',status.ready);
  const hours=Math.floor(status.uptimeSeconds/3600),minutes=Math.floor(status.uptimeSeconds%3600/60);
  const values=[['在线玩家',status.onlinePlayers],['运行时间',hours?`${hours} 小时 ${minutes} 分钟`:`${minutes} 分钟`],['JVM 内存',`${status.usedMemoryMb} / ${status.maxMemoryMb} MB`]];
  statusStrip.replaceChildren(...values.map(([label,value])=>el('div',{},el('dt',{text:label}),el('dd',{text:value}))));
}
function renderPending(){
  document.querySelector('#pending-operation')?.remove();
  if(!pendingOperation()||!main?.isConnected)return;
  const recovery=el('section',{id:'pending-operation',class:'pending-banner'},el('span',{text:'上次操作结果尚未确认。核对时会沿用原请求编号。'}),button('核对上次操作',async()=>{
    if(state.busy)return;state.busy=true;
    try{const result=await recoverOperation();if(result)showResult(result);await refresh();}
    catch(error){notice('结果待核对',[error.message,'请在操作记录中核对原请求编号，必要时检查玩家状态。']);}
    finally{state.busy=false;renderPending();}
  },'refresh'),button('已人工核对',async()=>{
    if(await confirmOperation('结束待核对状态',['请先查看操作记录和玩家状态。此操作只解除网页的等待状态，不会再次执行上次操作。'],true)){
      acknowledgePending();renderPending();
    }
  }));
  main.prepend(recovery);
}
window.addEventListener('gm-session-expired',()=>connectionScreen('连接已过期，请重新连接。'));
// Drop fragments from old launcher links before requesting or rendering anything.
if(location.hash)history.replaceState(null,'',location.pathname+location.search);
await connect();
