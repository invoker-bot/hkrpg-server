import {el,icon,button,searchInput,empty,table,renderSummary} from './ui.js';

export function mountPlayers(main,inspector,state) {
  let query='';
  const list=el('div');
  const count=el('span',{class:'muted'});
  const toolbar=el('div',{class:'toolbar'},searchInput('搜索昵称或 UID',value=>{query=value.toLowerCase();renderList();}),count);
  main.append(toolbar,list);
  const renderList=()=>{
    const rows=state.players.filter(p=>p.name.toLowerCase().includes(query)||String(p.uid).includes(query));
    count.textContent=`${rows.length} 位在线玩家`;
    list.replaceChildren(rows.length?table(['玩家','UID','等级','世界等级','操作'],rows.map(player=>{
      const selected=state.target?.uid===player.uid;
      const pick=button('选择玩家',()=>state.setTarget(player.uid),'arrow');pick.classList.add('icon-button');pick.setAttribute('aria-label',`选择 ${player.name} UID ${player.uid}`);
      return el('tr',{class:selected?'selected':''},
        el('td',{},el('button',{type:'button',class:'player-cell player-select',onclick:()=>state.setTarget(player.uid),'aria-label':`查看 ${player.name}`},el('span',{class:'avatar',text:Array.from(player.name)[0]||'拓'}),el('span',{},el('strong',{text:player.name}),el('small',{class:'online',text:player.inBattle?'战斗中':'在线'})))),
        el('td',{class:'mono',text:player.uid}),el('td',{text:player.level}),el('td',{text:player.worldLevel}),el('td',{},pick));
    })):empty(query?'未找到匹配的在线玩家。':'当前没有在线玩家，玩家进入游戏后会出现在这里。'));
  };
  const renderInspector=()=>{
    renderSummary(inspector,state);
    const p=state.target;
    if(p) inspector.append(el('div',{class:'properties'},
      el('div',{},el('span',{text:'开拓等级'}),el('strong',{text:p.level})),
      el('div',{},el('span',{text:'世界等级'}),el('strong',{text:p.worldLevel})),
      el('div',{class:'wide'},el('span',{text:'当前场景'}),el('strong',{class:'mono',text:p.floorId}))));
    const actions=el('section',{class:'action-group'},el('h3',{text:'快捷操作'}));
    for(const [action,label,symbol] of [['heal','恢复生命','heal'],['refill','补满秘技点','bolt'],['unstuck','解除卡死','warning']]) {
      const b=button(label,()=>state.run({action,uid:p.uid},label,[`目标：${p.name} · UID ${p.uid}`,action==='unstuck'?'将返回当前场景起点，战斗中不可执行。':'将立即更新当前队伍。']),symbol);
      b.disabled=!p || state.busy || action==='unstuck'&&p.inBattle;actions.append(b);
    }
    const more=el('section',{class:'action-group'},el('h3',{text:'更多操作'}));
    for(const [page,label,symbol,primary] of [['resources','发放物品 / 角色','box',true],['mail','发送邮件','mail',false],['terminal','在终端中使用此目标','terminal',false]]) {
      const b=button(label,()=>state.navigate(page),symbol,primary);b.disabled=!p;more.append(b);
    }
    inspector.append(actions,more,el('p',{class:'inspector-note',text:'执行结果会记录在操作记录中。'}));
  };
  renderList();renderInspector();
  return ()=>{renderList();renderInspector();};
}
