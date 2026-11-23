'use strict';
const $=id=>document.getElementById(id),esc=v=>String(v??'—').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const state={revision:0,rows:[],currencies:[],holidays:[],providers:[],editIndex:null};
async function api(path,options={}){const r=await fetch('/api'+path,{...options,headers:{'Content-Type':'application/json'}});const data=await r.json();if(!r.ok)throw Error(data.error||data.message||`HTTP ${r.status}`);return data;}
const fmt=(v,d=2)=>v==null||v===''?'—':Number(v).toLocaleString('en-US',{maximumFractionDigits:d});
const DAY={MON:'周一',TUE:'周二',WED:'周三',THU:'周四',FRI:'周五',SAT:'周六',SUN:'周日',HOLIDAY:'节假日'};
const VENUE={NONE:'无需换汇',TREASURY:'TREASURY · 资金部',PROVIDER:'PROVIDER · 合作方换汇'};
const REASON={FX_TREASURY_CLOSED:'资金部窗口关闭',FX_PROVIDER_CLOSED:'合作方窗口关闭',FX_TICKET_LIMIT:'超窗口单笔限额'};
const pair=w=>`${w.debit}→${w.payment}`;
function render(){
 const o=$('filterOwner').value,p=$('filterPair').value;
 const rows=state.rows.filter(r=>(!o||r.window.provider===o)&&(!p||pair(r.window)===p));
 $('rowCount').textContent=`外汇窗口（共 ${rows.length} 行）`;
 $('rows').innerHTML=rows.map(r=>{const w=r.window;const extra=Number(w.extraBps)>0;
  return `<tr class="${state.editIndex===r.index?'row-selected':''}"><td>#${r.index}</td><td>${w.provider==='TREASURY'?'资金部 TREASURY':esc(w.provider)}</td><td>${esc(pair(w))}</td><td>${w.days.map(d=>esc(DAY[d]||d)).join('、')}</td><td>${esc((w.start||'').slice(0,5))}–${esc((w.end||'').slice(0,5))}</td><td>${esc(fmt(w.spreadBps))} bp</td><td>${extra?`＋${esc(fmt(w.extraBps))} bp`:'—'}</td><td>${w.maxUsd==null?'不限':esc(fmt(w.maxUsd))}</td><td><button class="btn secondary tiny" data-edit="${r.index}">编辑</button></td></tr>`;}).join('')||'<tr><td colspan="9">暂无符合条件的窗口。</td></tr>';
 $('holidays').textContent=state.holidays.length?`配置的香港节假日：${state.holidays.join('、')}（资金部窗口当天关闭；合作方窗口仅当该行勾选 HOLIDAY 时开放）`:'当前未配置香港节假日（演示）。资金部窗口周末关闭；如需演示节假日效果，可在窗口行的日期中勾选 HOLIDAY 并在此添加日期。';
}
const ownerOptions=sel=>`<option value="TREASURY" ${sel==='TREASURY'?'selected':''}>TREASURY · 资金部</option>`+state.providers.map(p=>`<option value="${esc(p.code)}" ${p.code===sel?'selected':''}>${esc(p.code)} · ${esc(p.name)}</option>`).join('');
const currencyOptions=sel=>state.currencies.map(c=>`<option value="${esc(c)}" ${c===sel?'selected':''}>${esc(c)}</option>`).join('');
function apply(data){
 state.rows=data.rows;state.revision=data.revision;state.currencies=[...data.currencies].sort();state.holidays=data.hongKongHolidays||[];
 $('revision').textContent=`配置版本 ${state.revision}`;
 const owners=[...new Set(data.rows.map(r=>r.window.provider))];
 $('filterOwner').innerHTML='<option value="">全部渠道</option>'+owners.map(o=>`<option value="${esc(o)}">${o==='TREASURY'?'资金部 TREASURY':esc(o)}</option>`).join('');
 const pairs=[...new Set(data.rows.map(r=>pair(r.window)))].sort();
 $('filterPair').innerHTML='<option value="">全部货币对</option>'+pairs.map(p=>`<option value="${esc(p)}">${esc(p)}</option>`).join('');
 for(const id of ['simDebit','simPayment','winDebit','winPayment'])$(id).innerHTML=currencyOptions($(id).value||(id.endsWith('Debit')?'HKD':'USD'));
 if(!$('winDays').children.length)$('winDays').innerHTML=Object.keys(DAY).map(d=>`<label><input type="checkbox" value="${d}">${esc(DAY[d])}${d==='HOLIDAY'?' *':''}</label>`).join('');
 render();
}
function fillForm(index){
 const row=typeof index==='number'?state.rows.find(r=>r.index===index):null;
 state.editIndex=row?row.index:null;const w=row?row.window:{provider:'TREASURY',debit:'HKD',payment:'USD',days:['MON','TUE','WED','THU','FRI'],start:'09:00',end:'17:30',spreadBps:10,extraBps:0,maxUsd:null};
 $('winProvider').innerHTML=ownerOptions(w.provider);
 $('winDebit').innerHTML=currencyOptions(w.debit);$('winPayment').innerHTML=currencyOptions(w.payment);
 $('winStart').value=(w.start||'').slice(0,5);$('winEnd').value=(w.end||'').slice(0,5);
 $('winSpread').value=w.spreadBps;$('winExtra').value=w.extraBps??0;$('winMax').value=w.maxUsd??'';
 document.querySelectorAll('#winDays input').forEach(c=>c.checked=(w.days||[]).includes(c.value));
 $('winDelete').disabled=!row;
 $('formTitle').textContent=row?`编辑外汇窗口 · #${row.index} ${w.provider}/${pair(w)}`:'新增外汇窗口';
 $('winError').textContent='';$('winGood').textContent='';
 render();
}
$('filterOwner').onchange=render;$('filterPair').onchange=render;$('query').onclick=render;
$('refresh').onclick=async()=>{try{apply(await api('/c10/windows'));fillForm();$('pageError').textContent='';}catch(e){$('pageError').textContent=e.message;}};
$('rows').onclick=e=>{const b=e.target.closest('[data-edit]');if(b){fillForm(Number(b.dataset.edit));$('formPanel').scrollIntoView({behavior:'smooth'});}};
$('winCancel').onclick=()=>fillForm();
$('winForm').onsubmit=async e=>{
 e.preventDefault();$('winError').textContent='';$('winGood').textContent='';$('winSave').disabled=true;
 try{
  const days=[...document.querySelectorAll('#winDays input:checked')].map(c=>c.value);
  if(!days.length)throw Error('至少勾选一个窗口日期');
  if(!$('winStart').value||!$('winEnd').value)throw Error('窗口起止时间必填');
  const window={provider:$('winProvider').value,debit:$('winDebit').value,payment:$('winPayment').value,days,
   start:$('winStart').value+':00',end:$('winEnd').value+':00',spreadBps:Number($('winSpread').value),
   extraBps:Number($('winExtra').value||0),maxUsd:$('winMax').value===''?null:Number($('winMax').value)};
  if(!(window.spreadBps>=0)||!(window.extraBps>=0))throw Error('点差必须为不小于0的数字');
  if(window.maxUsd!=null&&window.maxUsd<0)throw Error('单笔限额必须非负');
  apply(await api('/c10/windows',{method:'PUT',body:JSON.stringify({revision:state.revision,index:state.editIndex,window})}));
  const hit=state.rows.find(r=>r.window.provider===window.provider&&pair(r.window)===`${window.debit}→${window.payment}`&&r.window.days.join()===days.join());
  fillForm(hit?.index??null);
  $('winGood').textContent=`已保存，配置版本 ${state.revision}。下一次路由试算即按新窗口判定 C10 / 计算 C7 点差。`;
  $('simResult').className='notice';$('simResult').textContent='窗口已保存，请重新执行模拟查看最新判定。';
 }catch(err){$('winError').textContent=err.message;}finally{$('winSave').disabled=false;}
};
$('winDelete').onclick=async()=>{
 if(state.editIndex==null)return;
 if(!confirm(`确定删除外汇窗口 #${state.editIndex}？删除后该渠道货币对不再生成候选。`))return;
 $('winError').textContent='';$('winGood').textContent='';
 try{
  apply(await api('/c10/windows/delete',{method:'POST',body:JSON.stringify({revision:state.revision,index:state.editIndex})}));
  fillForm();$('winGood').textContent='窗口已删除。';
 }catch(err){$('winError').textContent=err.message;}
};
function step(n,title,value,detail){return `<div class="step"><span class="num">${n}</span><div><b>${esc(title)}</b><div style="margin-top:6px">${value}</div><small>${esc(detail)}</small></div></div>`;}
function channelBlock(ch,r){
 let n=1;const name=ch.venue==='TREASURY'?VENUE.TREASURY:ch.venue==='PROVIDER'?`${VENUE.PROVIDER} · ${esc(ch.owner)}`:VENUE.NONE;
 const total=ch.open?Number(ch.open.spreadBps)+Number(ch.open.extraBps):null;
 const winLine=ch.venue==='NONE'?'无需换汇，不校验窗口':ch.windows.length?ch.windows.map(w=>`<div>${w.days.map(d=>esc(DAY[d]||d)).join('、')} ${esc((w.start||'').slice(0,5))}–${esc((w.end||'').slice(0,5))} · ${esc(fmt(w.spreadBps))}bp${Number(w.extraBps)>0?`＋${esc(fmt(w.extraBps))}bp`:''}${w.maxUsd!=null?` · 上限 ${fmt(w.maxUsd)} USD`:''}</div>`).join(''):'未配置该渠道货币对窗口';
 const verdict=ch.windows.length===0?'未配置窗口 → <b>不生成该渠道候选</b>':ch.passed?`<b style="color:#0a8745">通过</b> · 命中窗口点差合计 ${esc(fmt(total))}bp（进入 C7 换汇成本）`:`<b style="color:#bf3b1f">${esc(REASON[ch.reason]||ch.reason)} · ${esc(ch.reason)}</b>${ch.nextWindow?` · 下一窗口 ${esc(ch.nextWindow.toString().replace('T',' ').slice(0,16))} HKT`:ch.reason==='FX_TICKET_LIMIT'?' · 本笔金额超过全部可用窗口单笔限额，无下一窗口可等':''}`;
 return `<div class="channel ${ch.passed||ch.windows.length===0?'':'closed'}"><header><h3>${name}</h3><span>${ch.passed?'<span class="ok">PASS</span>':ch.windows.length===0?'<span class="muted">无候选</span>':`<span class="bad">${esc(ch.reason)}</span>`}</span></header>
 ${step(n++,'本笔美元等价金额',`${fmt(r.amountUsd)} USD`,'交易金额 × HSG 中间汇率 ÷ USD 汇率；PROVIDER 渠道限额按此比较。')}
 ${step(n++,'该渠道窗口记录',winLine,'未配置窗口的渠道不生成候选；资金部窗口在香港节假日一律关闭。')}
 ${step(n++,`判定（${ch.venue==='NONE'?'无需换汇':'窗口 ＋ 限额'}）`,verdict,`C10 为过滤器：不通过的候选被剔除并保留原因码，随候选列表返回；全部渠道仅 C10 失败时整体 REFER FX_WINDOW_CLOSED。`)}
 </div>`;
}
$('simForm').onsubmit=async e=>{
 e.preventDefault();$('simulate').disabled=true;
 try{
  const req={debitCurrency:$('simDebit').value,paymentCurrency:$('simPayment').value,amount:Number($('simAmount').value),at:$('at').value?$('at').value+'+08:00':null};
  const r=await api('/c10/simulate',{method:'POST',body:JSON.stringify(req)});
  const fail=r.status!=='PASS';
  const verdict=fail&&r.status==='NO_FX_VENUE'
   ?`<div class="verdict fail"><span>C10 结果</span><b>NO_FX_VENUE</b><small>没有任何外汇渠道支持该货币对：全流程路由不生成候选，返回 REFER NO_FX_VENUE（8.1），需新增外汇渠道配置</small></div>`
   :`<div class="verdict ${fail?'fail':''}"><span>C10 结果</span><b>${fail?esc(r.status):'PASS'}</b><small>${fail?`全部渠道未通过，整体返回 REFER FX_WINDOW_CLOSED${r.nextFxWindow?`，下一窗口 ${esc(r.nextFxWindow.toString().replace('T',' ').slice(0,16))} HKT`:''}`:'至少一个渠道通过，候选保留进入后续打分（通过的渠道点差进入 C7 换汇成本）'}</small></div>`;
  $('simResult').className='';
  $('simResult').innerHTML=verdict+(r.channels.length?r.channels.map(c=>channelBlock(c,r)).join(''):'')+`<div class="notice">${esc(r.note)} · 配置版本 ${r.revision}</div>`;
 }catch(err){$('simResult').className='err';$('simResult').textContent=err.message;}finally{$('simulate').disabled=false;}
};
document.querySelectorAll('[data-sample]').forEach(b=>b.onclick=()=>{
 const set=(debit,payment,amount,at)=>{$('simDebit').value=debit;$('simPayment').value=payment;$('simAmount').value=amount;$('at').value=at.slice(0,16);};
 const s=b.dataset.sample;
 if(s==='open')set('HKD','USD',20000,'2026-11-23T09:05:00+08:00');
 else if(s==='sat')set('HKD','USD',20000,'2026-11-21T10:00:00+08:00');
 else if(s==='wise')set('HKD','AUD',20000,'2026-11-21T14:00:00+08:00');
 else if(s==='ticket')set('HKD','KRW',300000000,'2026-11-23T10:00:00+08:00');
 else if(s==='novenu')set('HKD','EUR',10000,'2026-11-23T10:00:00+08:00');
 else set('USD','USD',10000,'2026-11-23T10:00:00+08:00');
});
(async()=>{
 try{
  const [providers,data]=await Promise.all([api('/providers'),api('/c10/windows')]);
  state.providers=providers;
  apply(data);
  fillForm();
 }catch(e){$('pageError').textContent=e.message;}
})();
