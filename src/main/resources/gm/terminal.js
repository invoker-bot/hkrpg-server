import {api} from './api.js';
import {el,button,field,numberInput,searchInput,empty,renderSummary} from './ui.js';
export function mountTerminal(main,inspector,state) {
  let commands=[];
  const input=el('input',{class:'mono',required:true,maxlength:2048,placeholder:'例如：heal 或 avatar cur lv80 r6'});
  const uid=numberInput(state.target?.uid||0,0,2147483647);
  let previousTarget=state.target?.uid;
  const output=el('pre',{class:'terminal-output',tabindex:0,text:'等待执行指令。'});
  const execute=button('执行指令',()=>form.requestSubmit(),'terminal',true);
  const form=el('form',{class:'terminal-form'},field('指令',input,'目标在 UID 栏单独指定，指令中无需填写 @UID。'),field('目标 UID',uid,'0 表示不指定玩家。离线解卡可手动填写 UID。'),execute);
  form.addEventListener('submit',async event=>{
    event.preventDefault();
    const command=input.value.trim();
    const label=command.split(/\s+/)[0].replace(/^[/!]/,'').toLowerCase();
    const known=commands.find(c=>c.label===label);
    const dangerous=known?.dangerous || ['perm','ga'].includes(label);
    const result=await state.run({action:'command',uid:Number(uid.value),command,confirm:dangerous?command:null},dangerous?'确认管理指令':'执行指令',
      [`目标 UID：${uid.value}`,command,...(dangerous?['该指令会批量修改资源、账号、权限或连接状态，请核对完整指令。']:[])],dangerous);
    if(result) output.textContent=`> ${command}\n${result.output.join('\n')}`;
  });
  main.append(form,el('h3',{text:'执行输出'}),output,el('p',{class:'muted',text:'“指令已返回”表示处理器已返回；请结合输出和玩家状态核对结果。'}));
  const list=el('div',{class:'command-list'});
  let filter='';
  const renderList=()=>{
    const matching=commands.filter(c=>(c.label+' '+c.description).toLowerCase().includes(filter));
    list.replaceChildren(...matching.map(command=>{
      const b=button(command.label,()=>{input.value=command.label;input.focus();},'terminal');
      return el('div',{class:'command-reference'},b,el('small',{class:'mono',text:command.description}),command.dangerous?el('small',{class:'warning-text',text:'执行前需确认'}):null);
    }));
    if(!matching.length) list.append(empty('未找到匹配指令。'));
  };
  const update=()=>{
    renderSummary(inspector,state);
    inspector.append(el('section',{class:'action-group'},el('h3',{text:'指令参考'}),searchInput('搜索指令',value=>{filter=value.toLowerCase();renderList();}),list));
    if(previousTarget!==state.target?.uid) uid.value=state.target?.uid||0;
    previousTarget=state.target?.uid;execute.disabled=state.busy;
  };
  api('commands').then(data=>{commands=data;renderList();}).catch(error=>list.replaceChildren(empty(error.message)));
  update();return update;
}
