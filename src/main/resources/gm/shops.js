import {api} from './api.js';
import {el,button,field,searchInput,empty,table} from './ui.js';

const refreshNames={DAILY:'每日',WEEK:'每周',MONTH:'每月',CYCLE:'每周期'};
const key=goods=>goods.shopId+'/'+goods.goodsId;
const price=goods=>goods.costs.map(c=>c.name+' × '+c.amount).join(' + ')||'无货币费用';
const limit=goods=>goods.limitTimes>0?goods.limitTimes+' 次 · '+(refreshNames[goods.refreshType]||'一次性'):'不限购';

export function mountShops(main,inspector,state) {
  let query='',shopId='',status='',offset=0,sequence=0,loading=false,selected=null,pageItems=[];
  const checked=new Map(),shops=el('select',{'aria-label':'商店'}),statuses=el('select',{'aria-label':'上架状态'});
  statuses.append(...[['','全部状态'],['listed','已上架'],['unlisted','已下架']].map(([value,text])=>el('option',{value,text})));
  shops.append(el('option',{value:'',text:'全部商店'}));
  const list=el('div',{class:'shop-results'}),count=el('p',{class:'muted',role:'status'});
  const selectedCount=el('strong'),clear=button('清除勾选',()=>{checked.clear();render();});
  const enable=button('上架勾选商品',()=>change([...checked.values()],true),'refresh',true);
  const disable=button('下架勾选商品',()=>change([...checked.values()],false),'close');
  const bulk=el('div',{class:'shop-bulk'},selectedCount,clear,enable,disable);
  const previous=button('上一页',()=>{offset=Math.max(0,offset-30);load();});
  const next=button('下一页',()=>{offset+=30;load();});
  const search=searchInput('搜索商品名、物品 ID 或商品 ID',value=>{query=value;filter();});
  const refresh=button('刷新商品',load,'refresh');
  function filter(){shopId=shops.value;status=statuses.value;offset=0;selected=null;checked.clear();load();}
  shops.addEventListener('change',filter);statuses.addEventListener('change',filter);
  main.append(el('div',{class:'shop-filters'},field('商店',shops),field('上架状态',statuses)),
    el('div',{class:'toolbar'},search,refresh),bulk,count,list,el('div',{class:'pagination'},previous,next));

  function render(){
    const blocked=state.busy||loading;
    selectedCount.textContent='已勾选 '+checked.size+' / 500 个';
    clear.disabled=blocked||!checked.size;
    enable.disabled=blocked||![...checked.values()].some(g=>!g.listed);
    disable.disabled=blocked||![...checked.values()].some(g=>g.listed);
    refresh.disabled=blocked;
    for(const input of list.querySelectorAll('input[data-goods-key]')){
      input.checked=checked.has(input.dataset.goodsKey);input.disabled=blocked;
    }
    const pageCheck=list.querySelector('input[data-page-check]');
    if(pageCheck){
      const n=pageItems.filter(g=>checked.has(key(g))).length;
      pageCheck.checked=pageItems.length>0&&n===pageItems.length;
      pageCheck.indeterminate=n>0&&n<pageItems.length;pageCheck.disabled=blocked||!pageItems.length;
    }
    inspector.replaceChildren(el('p',{class:'eyebrow',text:'全服商店管理'}),
      el('h2',{text:selected?selected.name:'选择商品'}));
    if(!selected){
      inspector.append(el('p',{class:'inspector-note',text:'选择商品查看价格与限购，可单个或勾选多个商品上下架。无需选择玩家。状态在重启后保留。'}));return;
    }
    const goods=selected;
    inspector.append(el('p',{class:'mono muted',text:'商品 #'+goods.goodsId+' · 物品 #'+goods.itemId}),
      el('section',{class:'action-group'},el('h3',{text:goods.shopName+' · #'+goods.shopId}),
        el('p',{class:goods.listed?'online-text':'warning-text',text:goods.listed?'已上架':'已下架'}),
        el('p',{text:'每份：'+goods.name+' × '+goods.itemCount}),el('p',{text:'价格：'+price(goods)}),
        el('p',{text:'限购：'+limit(goods)})));
    if(goods.rotating)inspector.append(el('p',{class:'muted',text:'轮换商品，此处显示当前轮换物品；上下架影响整个商品位。'}));
    const toggle=button(goods.listed?'下架商品':'上架商品',()=>change([goods],!goods.listed),goods.listed?'close':'refresh',!goods.listed);
    toggle.disabled=blocked;inspector.append(toggle,
      el('p',{class:'inspector-note',text:'全服生效。上架沿用原价格、数量、门槛和限购记录；下架后不能继续购买。操作后请重新打开游戏内商店。'}));
  }

  async function change(rows,listed){
    if(state.busy||loading||!rows.length)return;
    const title=listed?'确认上架商品':'确认下架商品';
    const result=await state.run({action:listed?'shop-list':'shop-unlist',shopGoods:rows.map(g=>({shopId:g.shopId,goodsId:g.goodsId}))},title,[
      '全服生效，共 '+rows.length+' 个商品。',
      rows.slice(0,5).map(g=>g.shopName+' / '+g.name+' #'+g.goodsId).join('、')+(rows.length>5?' 等':''),
      listed?'恢复商品上架，沿用原价格、数量、门槛与玩家限购记录。':'停止展示和购买，之后可重新上架。',
      '已处于目标状态的商品保持原样。请重新打开游戏内商店。',
    ]);
    if(result){if(result.state==='completed')checked.clear();await load();}
  }

  function selectRow(goods,selected){
    if(selected){if(checked.has(key(goods))||checked.size<500)checked.set(key(goods),goods);}
    else checked.delete(key(goods));
  }

  async function load(){
    const generation=++sequence;loading=true;render();list.setAttribute('aria-busy','true');
    previous.disabled=true;next.disabled=true;
    try{
      const data=await api('shops?'+new URLSearchParams({q:query,shopId:shopId||0,state:status,offset,limit:30}));
      if(generation!==sequence||!main.isConnected)return;
      if(offset>0&&!data.items.length&&data.total<=offset){offset=Math.max(0,Math.floor((data.total-1)/30)*30);return load();}
      shops.replaceChildren(el('option',{value:'',text:'全部商店'}),...data.shops.map(s=>el('option',{value:s.id,text:s.name+' · #'+s.id})));
      shops.value=shopId;pageItems=data.items;
      count.textContent='共 '+data.total+' 个商品 · 本页 '+pageItems.length+' 个';
      selected=selected?pageItems.find(g=>key(g)===key(selected))||null:null;
      for(const goods of pageItems)if(checked.has(key(goods)))checked.set(key(goods),goods);
      const pageCheck=el('input',{type:'checkbox',class:'shop-check','aria-label':'勾选本页商品',onchange:event=>{
        for(const goods of pageItems)selectRow(goods,event.target.checked);render();
      }});pageCheck.dataset.pageCheck='true';
      list.replaceChildren(pageItems.length?table([pageCheck,'商店','商品 / 物品','价格 / 限购','状态','详情'],pageItems.map(goods=>{
        const input=el('input',{type:'checkbox',class:'shop-check','aria-label':'勾选 '+goods.name+' #'+goods.goodsId,
          onchange:event=>{selectRow(goods,event.target.checked);render();}});input.dataset.goodsKey=key(goods);
        return el('tr',{class:selected&&key(selected)===key(goods)?'selected':''},
          el('td',{},input),el('td',{},el('strong',{text:goods.shopName}),el('small',{class:'muted',text:'#'+goods.shopId})),
          el('td',{},el('strong',{text:goods.name+' × '+goods.itemCount}),el('small',{class:'muted',text:'商品 #'+goods.goodsId+' · 物品 #'+goods.itemId})),
          el('td',{},el('span',{text:price(goods)}),el('small',{class:'muted',text:limit(goods)})),
          el('td',{class:goods.listed?'online-text':'muted',text:goods.listed?'已上架':'已下架'}),
          el('td',{},button('查看',()=>{selected=goods;render();
            for(const row of list.querySelectorAll('tbody tr'))row.classList.remove('selected');
            list.querySelectorAll('tbody tr')[pageItems.indexOf(goods)]?.classList.add('selected');
            if(matchMedia('(max-width:740px)').matches)inspector.scrollIntoView({block:'start'});
          })));
      })):empty('未找到匹配商品，请调整商店、状态或搜索条件。'));
      previous.disabled=offset===0;next.disabled=offset+pageItems.length>=data.total;
    }catch(error){if(generation===sequence&&main.isConnected){list.replaceChildren(empty(error.message),button('重新加载',load));count.textContent='商品加载失败';}}
    finally{if(generation===sequence){loading=false;list.removeAttribute('aria-busy');render();}}
  }
  load();return render;
}
