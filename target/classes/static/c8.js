'use strict';
const $=id=>document.getElementById(id),esc=v=>String(v??'—').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const state={revision:0,rows:[],providers:[],editIndex:null};
async function api(path,options={}){const r=await fetch('/api'+path,{...options,headers:{'Content-Type':'application/json'}});const data=await r.json();if(!r.ok)throw Error(data.error||data.message||`HTTP ${r.status}`);return data;}
const fmt=(v,d=2)=>v==null||v===''?'—':Number(v).toLocaleString('en-US',{maximumFractionDigits:d});
const show=v=>(v==null||v===''||v==='ALL')?'全部':String(v);
const range=(min,max)=>min==null&&max==null?'不限':(min==null?'≤':(max==null?'≥':`${fmt(min)}–`))+(max==null?fmt(min??''):fmt(max));
function render(){
 const p=$('filterProvider').value;
 const rows=state.rows.filter(r=>!p||r.rule.provider===p);
 $('rowCount').textContent=`偏好规则（共 ${rows.length} 条）`;
 $('rows').innerHTML=rows.map(r=>{const x=r.rule;
  return `<tr class="${state.editIndex===r.index?'row-selected':''}"><td>#${r.index}</td><td>${esc(x.provider)}</td><td>${esc(show(x.currency))}</td><td>${esc(show(x.country))}</td><td>${esc(show(x.region))}</td><td>${esc(range(x.minUsd,x.maxUsd))}</td><td>${esc(show(x.paymentType))}</td><td>${esc(show(x.segment))}</td><td><span class="points-badge">＋${esc(x.points)}</span></td><td><button class="btn secondary tiny" data-edit="${r.index}">编辑</button></td></tr>`;}).join('')||'<tr><td colspan="10">暂无符合条件的规则。</td></tr>';
}
const providerOptions=sel=>state.providers.map(p=>`<option value="${esc(p.code)}" ${p.code===sel?'selected':''}>${esc(p.code)} · ${esc(p.name)}</option>`).join('');
function apply(data){
 state.rows=data.rows;state.revision=data.revision;
 $('revision').textContent=`配置版本 ${state.revision}`;
 $('filterProvider').innerHTML='<option value="">全部合作方</option>'+[...new Set(data.rows.map(r=>r.rule.provider))].map(p=>`<option value="${esc(p)}">${esc(p)}</option>`).join('');
 $('ruleProvider').innerHTML=providerOptions($('ruleProvider').value);
 render();
}
function fillForm(index){
 const row=typeof index==='number'?state.rows.find(r=>r.index===index):null;
 state.editIndex=row?row.index:null;const x=row?row.rule:{provider:state.providers[0]?.code,currency:'',country:'',region:'',minUsd:null,maxUsd:null,paymentType:'',segment:'',points:100};
 $('ruleProvider').innerHTML=providerOptions(x.provider);
 $('ruleCurrency').value=x.currency||'';$('ruleCountry').value=x.country||'';$('ruleRegion').value=x.region||'';
 $('ruleMin').value=x.minUsd??'';$('ruleMax').value=x.maxUsd??'';
 $('ruleType').value=x.paymentType||'';$('ruleSegment').value=x.segment||'';$('rulePoints').value=x.points;
 $('ruleDelete').disabled=!row;
 $('formTitle').textContent=row?`编辑偏好规则 · #${row.index}`:'新增偏好规则';
 $('ruleError').textContent='';$('ruleGood').textContent='';
 render();
}
$('filterProvider').onchange=render;$('query').onclick=render;
$('refresh').onclick=async()=>{try{apply(await api('/c8/rules'));fillForm();$('pageError').textContent='';}catch(e){$('pageError').textContent=e.message;}};
$('rows').onclick=e=>{const b=e.target.closest('[data-edit]');if(b){fillForm(Number(b.dataset.edit));$('formPanel').scrollIntoView({behavior:'smooth'});}};
$('ruleCancel').onclick=()=>fillForm();
$('ruleForm').onsubmit=async e=>{
 e.preventDefault();$('ruleError').textContent='';$('ruleGood').textContent='';$('ruleSave').disabled=true;
 try{
  const clean=s=>s&&s.trim()?s.trim().toUpperCase():null;
  const rule={provider:$('ruleProvider').value,currency:clean($('ruleCurrency').value),country:clean($('ruleCountry').value),
   region:clean($('ruleRegion').value),minUsd:$('ruleMin').value===''?null:Number($('ruleMin').value),maxUsd:$('ruleMax').value===''?null:Number($('ruleMax').value),
   paymentType:$('ruleType').value||null,segment:clean($('ruleSegment').value),points:Number($('rulePoints').value)};
  if(!(rule.points>=1&&rule.points<=100))throw Error('奖励分数必须为 1–100 的整数');
  if(rule.minUsd!=null&&rule.minUsd<0)throw Error('金额下限必须非负');
  if(rule.maxUsd!=null&&(rule.maxUsd<0||(rule.minUsd!=null&&rule.maxUsd<rule.minUsd)))throw Error('金额上限必须非负且不小于下限');
  apply(await api('/c8/rules',{method:'PUT',body:JSON.stringify({revision:state.revision,index:state.editIndex,rule})}));
  fillForm(state.editIndex);
  $('ruleGood').textContent=`已保存，配置版本 ${state.revision}。下一次路由试算即按新规则计算 C8。`;
  $('simResult').className='notice';$('simResult').textContent='规则已保存，请重新试算查看最新评分。';
 }catch(err){$('ruleError').textContent=err.message;}finally{$('ruleSave').disabled=false;}
};
$('ruleDelete').onclick=async()=>{
 if(state.editIndex==null)return;
 if(!confirm(`确定删除偏好规则 #${state.editIndex}？`))return;
 $('ruleError').textContent='';$('ruleGood').textContent='';
 try{
  apply(await api('/c8/rules/delete',{method:'POST',body:JSON.stringify({revision:state.revision,index:state.editIndex})}));
  fillForm();
  $('ruleGood').textContent='规则已删除。';
 }catch(err){$('ruleError').textContent=err.message;}
};
$('simForm').onsubmit=async e=>{
 e.preventDefault();$('simulate').disabled=true;
 try{
  const clean=s=>s&&s.trim()?s.trim().toUpperCase():null;
  const req={paymentCurrency:clean($('simCurrency').value),beneficiaryBankCountry:clean($('simCountry').value),
   beneficiaryRegion:clean($('simRegion').value),amount:Number($('simAmount').value),
   paymentType:$('simType').value,clientSegment:clean($('simSegment').value)};
  const r=await api('/c8/simulate',{method:'POST',body:JSON.stringify(req)});
  const c=r.calculation;
  const condDiv=(cs)=>`<div class="cond">${cs.map(x=>`<div><b>${esc(x.label)}${x.wildcard?'<span class="wild"> · 匹配全部</span>':''}</b>配置：${esc(x.configured)} ｜ 交易：${esc(x.actual)} <span class="${x.matched?'hit':'miss'}">${x.matched?'✓ 命中':'✗ 不匹配'}</span></div>`).join('')}</div>`;
  const ruleBlocks=c.evaluations.map(ev=>`<div class="step"><span class="num">${ev.matched?'✓':'✗'}</span><div><b>#${ev.index} → ${esc(ev.rule.provider)} ＋${esc(ev.rule.points)} 分 · ${ev.matched?'<span class="hit">命中</span>':'<span class="miss">未命中</span>'}</b>${condDiv(ev.conditions)}</div></div>`).join('');
  const providerScores=c.providers.map(p=>`<div class="provider-score"><span>${esc(p.provider)}${p.hitIndexes.length?` <span class="muted">命中规则 #${p.hitIndexes.join('、#')}</span>`:''}</span><span>累计 ${esc(p.rawPoints)} → <span class="pts">${esc(p.score)} 分</span></span></div>`).join('');
  const winner=c.providers.filter(p=>p.score>0).sort((a,b)=>b.score-a.score);
  $('simResult').className='';
  $('simResult').innerHTML=`<div class="score-big"><span>命中规则后各合作方偏好得分</span>${winner.length?winner.map(w=>`<b>${esc(w.provider)} ＋${esc(w.score)} 分</b>`).join(''):'<b>0 分</b>'}<small>合计封顶100；未命中或未配置规则得0分 · 交易美元等价 ${esc(fmt(r.txn.amountUsd))} USD</small></div>${ruleBlocks}<div style="margin-top:10px"><b style="font-size:13px">合作方得分汇总</b>${providerScores||'<div class="muted">尚无任何规则配置。</div>'}</div><div class="notice">${esc(r.note)}</div>`;
 }catch(err){$('simResult').className='err';$('simResult').textContent=err.message;}finally{$('simulate').disabled=false;}
};
document.querySelectorAll('[data-sample]').forEach(b=>b.onclick=()=>{
 $('simCurrency').value='USD';$('simRegion').value='';$('simSegment').value='STANDARD';$('simType').value='TRADE';
 if(b.dataset.sample==='kg'){$('simCountry').value='KG';$('simAmount').value=30000;}
 else if(b.dataset.sample==='million'){$('simCountry').value='US';$('simAmount').value=1000000;}
 else if(b.dataset.sample==='gd'){$('simCountry').value='CN';$('simRegion').value='CN-GD';$('simAmount').value=50000;}
 else{$('simCountry').value='DE';$('simCurrency').value='EUR';$('simAmount').value=5000;}
});
(async()=>{
 try{
  const [providers,data]=await Promise.all([api('/providers'),api('/c8/rules')]);
  state.providers=providers;
  apply(data);
  fillForm();
 }catch(e){$('pageError').textContent=e.message;}
})();
