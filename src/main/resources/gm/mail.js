import {el,button,field,numberInput,targetPicker,renderSummary} from './ui.js';
export function mountMail(main,inspector,state) {
  const form=el('form',{class:'editor-form'});
  let target=targetPicker(state);
  const title=el('input',{maxlength:80,required:true,placeholder:'例如：测试补给'});
  const sender=el('input',{maxlength:40,required:true,value:'GM'});
  const content=el('textarea',{maxlength:4000,required:true,rows:8,placeholder:'输入邮件正文'});
  const attachments=el('div',{class:'attachments'});
  const rows=[];
  const add=button('添加附件',()=>{
    if(rows.length>=5)return;
    const id=numberInput('',1,2147483647);id.required=true;id.placeholder='物品 ID';id.setAttribute('aria-label','附件物品 ID');
    const amount=numberInput(1,1,1000000);amount.setAttribute('aria-label','附件数量');amount.required=true;
    const row=el('div',{class:'attachment-row'},id,amount);
    const entry={row,id,amount};rows.push(entry);
    const remove=button('移除',()=>{rows.splice(rows.indexOf(entry),1);row.remove();add.disabled=false;},'close');
    remove.setAttribute('aria-label','移除附件');row.append(remove);
    attachments.append(row);add.disabled=rows.length>=5;
  },'box');
  const send=button('预览邮件',()=>form.requestSubmit(),'mail',true);
  send.disabled=!state.target || state.busy;
  form.append(field('目标玩家',target),el('div',{class:'form-columns'},field('邮件标题',title),field('发件人',sender)),field('邮件正文',content),el('h3',{text:'附件'}),el('p',{class:'muted',text:'最多 5 种，仅支持货币和可堆叠材料、消耗品。'}),attachments,add,el('div',{class:'form-actions'},send));
  form.addEventListener('submit',event=>{
    event.preventDefault();if(!state.target)return;
    const items=rows.map(r=>({itemId:Number(r.id.value),amount:Number(r.amount.value)}));
    state.run({action:'mail',uid:state.target.uid,title:title.value,sender:sender.value,content:content.value,attachments:items},'确认发送邮件',
      [`目标：${state.target.name} · UID ${state.target.uid}`,`标题：${title.value}`,`发件人：${sender.value}`,`正文：${content.value}`,...items.map(i=>`附件：#${i.itemId} × ${i.amount}`)]);
  });
  main.append(form);
  const update=()=>{
    renderSummary(inspector,state);
    inspector.append(el('p',{class:'inspector-note',text:'邮件会保存到玩家邮箱，并发送新邮件通知。有效期 30 天，附件由玩家在游戏内领取。'}));
    const next=targetPicker(state);target.replaceWith(next);target=next;
    send.disabled=!state.target || state.busy;
  };
  update();return update;
}
